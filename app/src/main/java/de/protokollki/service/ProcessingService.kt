package de.protokollki.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import de.protokollki.MainActivity
import de.protokollki.ProtokollApp
import de.protokollki.R
import de.protokollki.audio.MappedPcm16Audio
import de.protokollki.engine.DiskSpace
import de.protokollki.data.AppSettings
import de.protokollki.data.MeetingRepository
import de.protokollki.data.MeetingStatus
import de.protokollki.data.ProtocolMode
import de.protokollki.data.Speaker
import de.protokollki.engine.LlmException
import de.protokollki.engine.LocalLlm
import de.protokollki.engine.LocalLlmModel
import de.protokollki.engine.ModelManager
import de.protokollki.engine.ProtocolGenerator
import de.protokollki.engine.Transcriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verarbeitet Besprechungen im Hintergrund (auch bei ausgeschaltetem Bildschirm):
 * Sprechererkennung → Transkription → Protokoll → Aufgaben. Alles lokal.
 */
class ProcessingService : LifecycleService() {

    private data class Job(val meetingId: String, val summaryOnly: Boolean)

    private val queue = Channel<Job>(Channel.UNLIMITED)
    private val queued = mutableSetOf<String>()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var timedOut = false
    @Volatile private var currentLlm: LocalLlm? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var workerStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            startInForeground("Protokoll KI arbeitet …", 0f)
        } catch (e: Exception) {
            // z. B. Zeitlimit für Datendienste (Android 15) aufgebraucht: melden statt abstürzen
            android.util.Log.e(TAG, "startForeground fehlgeschlagen", e)
            intent?.getStringExtra(EXTRA_ID)?.let { id ->
                runCatching { fail(id, "Die Verarbeitung konnte nicht gestartet werden. Bitte die App öffnen und erneut versuchen.") }
            }
            if (!workerStarted) stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled.set(true)
                currentLlm?.cancel()
            }
            ACTION_PROCESS, ACTION_REGENERATE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                synchronized(queued) {
                    // Nach "Abbrechen" darf dieselbe Besprechung sofort neu eingereiht werden,
                    // auch wenn der abgebrochene Lauf noch ausläuft.
                    if (id in queued && !cancelled.get()) return START_NOT_STICKY
                    queued += id
                }
                MeetingRepository.update(id) {
                    it.copy(status = MeetingStatus.QUEUED, progress = 0f,
                        progressText = "Wartet auf Verarbeitung …", error = null)
                }
                queue.trySend(Job(id, intent.action == ACTION_REGENERATE))
            }
        }
        if (!workerStarted) {
            workerStarted = true
            lifecycleScope.launch { worker() }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15+: Datendienste dürfen höchstens 6 h pro Tag im Hintergrund laufen. Wird der Dienst
     * danach nicht binnen Sekunden beendet, beendet Android die App mit einem Absturz. Daher sofort
     * abbrechen und verständlich melden.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        android.util.Log.w(TAG, "Zeitlimit für Hintergrundverarbeitung erreicht")
        timedOut = true
        cancelled.set(true)
        currentLlm?.cancel()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private suspend fun worker() {
        acquireWakeLock()
        try {
            while (true) {
                val job = queue.tryReceive().getOrNull() ?: break
                if (timedOut) {
                    runCatching { fail(job.meetingId, TIMEOUT_MESSAGE) }
                    synchronized(queued) { queued -= job.meetingId }
                    continue
                }
                cancelled.set(false)
                try {
                    withContext(Dispatchers.Default) { run(job) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Darf den Dienst nie zum Absturz bringen (z. B. Speicher voll beim Speichern)
                    android.util.Log.e(TAG, "Verarbeitung abgebrochen", e)
                    runCatching { fail(job.meetingId, "Verarbeitung fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}") }
                }
                synchronized(queued) { queued -= job.meetingId }
            }
        } finally {
            releaseWakeLock()
            workerStarted = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun run(job: Job) {
        val id = job.meetingId
        val meeting = MeetingRepository.get(id) ?: return
        val set = ModelManager.state.value.set
        if (!ModelManager.isInstalled(set)) {
            fail(id, "Die KI-Modelle sind noch nicht heruntergeladen. Bitte in den Einstellungen herunterladen.")
            return
        }
        try {
            if (!job.summaryOnly || meeting.segments.isEmpty()) {
                transcribe(id, meeting.expectedSpeakers, set)
            }
            if (cancelled.get()) { abort(id); return }
            summarize(id, set)
            if (cancelled.get()) { abort(id); return }
            val done = MeetingRepository.update(id) {
                it.copy(status = MeetingStatus.DONE, progress = 1f, progressText = "")
            }
            if (done != null) notifyDone(done.id, done.title)
        } catch (e: LlmException) {
            if (cancelled.get()) abort(id) else fail(id, e.message ?: "Fehler im Sprachmodell")
        } catch (e: OutOfMemoryError) {
            System.gc()
            fail(id, "Zu wenig Arbeitsspeicher. Bitte andere Apps schließen oder ein kleineres Modellpaket wählen.")
        } catch (e: Throwable) {
            fail(id, if (DiskSpace.isDiskFull(e)) DiskSpace.FULL_MESSAGE
                else "Verarbeitung fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun transcribe(id: String, expectedSpeakers: Int, set: de.protokollki.engine.ModelSet) {
        progress(id, MeetingStatus.TRANSCRIBING, 0f, "Lade Aufnahme …")
        val meeting = MeetingRepository.get(id) ?: return
        // Nicht die ganze Aufnahme in den Speicher laden (3 h wären 690 MB als FloatArray),
        // sondern abschnittsweise direkt aus der Datei lesen.
        val scratch = File(cacheDir, "audio")
        scratch.listFiles()?.forEach { it.delete() }   // Reste eines abgebrochenen Laufs
        val samples = try {
            MappedPcm16Audio.open(meeting.audioFile, scratch)
        } catch (e: java.io.IOException) {
            if (DiskSpace.isDiskFull(e)) throw IllegalStateException(DiskSpace.FULL_MESSAGE)
            throw IllegalStateException("Die Aufnahme konnte nicht gelesen werden (${e.message}).")
        }
        samples.use { transcribeAudio(id, it, expectedSpeakers, set) }
    }

    private fun transcribeAudio(
        id: String,
        samples: MappedPcm16Audio,
        expectedSpeakers: Int,
        set: de.protokollki.engine.ModelSet,
    ) {
        if (samples.size < 16000) throw IllegalStateException("Die Aufnahme ist zu kurz.")
        val participants = MeetingRepository.get(id)?.participants.orEmpty()
        val duration = samples.size / 16000f
        MeetingRepository.update(id) { it.copy(durationSec = duration) }

        val transcriber = Transcriber(set, AppSettings.state.value.language)
        progress(id, MeetingStatus.TRANSCRIBING, 0.01f, "Erkenne Sprecher …")
        var t0 = System.currentTimeMillis()
        val turns = transcriber.diarize(samples, expectedSpeakers) { f ->
            progress(id, MeetingStatus.TRANSCRIBING, 0.2f * f, "Erkenne Sprecher …")
            !cancelled.get()
        }
        if (cancelled.get()) return
        de.protokollki.engine.Timing.since("sprecher", t0, "Audio ${duration.toInt()} s")
        val chunks = transcriber.planChunks(turns, samples)
        val speakerCount = (chunks.maxOfOrNull { it.speaker } ?: 0) + 1
        // Bekannte Stimmen erkennen (Stimmprofile)
        progress(id, MeetingStatus.TRANSCRIBING, 0.2f, "Erkenne bekannte Stimmen …")
        val known = runCatching {
            de.protokollki.engine.VoiceProfiles.identify(applicationContext, id, samples, chunks)
        }.getOrDefault(emptyMap())
        val speakers = (0 until speakerCount).map {
            val p = known[it]
            if (p != null) Speaker(it, p.name, p.id) else Speaker(it, "Person ${it + 1}")
        }
        MeetingRepository.update(id) { it.copy(speakers = speakers, segments = emptyList()) }

        val vocab = (participantTerms(participants) + de.protokollki.engine.Vocabulary.terms()).distinct()
        t0 = System.currentTimeMillis()
        val raw = transcriber.transcribe(samples, chunks) { done, total, partial ->
            MeetingRepository.update(id, persist = false) {
                it.copy(
                    status = MeetingStatus.TRANSCRIBING,
                    progress = 0.2f + 0.8f * done / total,
                    progressText = "Transkribiere … ${(100f * done / total).toInt()} %",
                    segments = partial.toList(),
                )
            }
            !cancelled.get()
        }
        if (cancelled.get()) return
        de.protokollki.engine.Timing.since("text", t0, "${raw.size} Beiträge")
        // Eigenes Wörterbuch: Namen und Fachbegriffe richtig schreiben
        val segments = if (vocab.isEmpty()) raw
        else raw.map { it.copy(text = de.protokollki.engine.Vocabulary.correct(it.text, vocab)) }
        // Nur Sprecher behalten, die tatsächlich Text haben
        val used = segments.map { it.speakerId }.toSet()
        // Namen aus dem Gespräch („Mein Name ist …“, „Frau Schmidt, …?“) für noch unbenannte Sprecher
        val guessed = runCatching { de.protokollki.engine.SpeakerNaming.guess(segments, participants) }
            .getOrDefault(emptyMap())
        val named = speakers.map { s ->
            val g = guessed[s.id]
            if (g != null && s.profileId == null && s.name.startsWith("Person ")) s.copy(name = g) else s
        }
        MeetingRepository.update(id) {
            it.copy(segments = segments, speakers = named.filter { s -> s.id in used })
        }
        if (segments.isEmpty()) throw IllegalStateException("In der Aufnahme wurde keine Sprache erkannt.")
    }

    /** „Frau Dr. Schmidt“ → „Schmidt“; „Anna Becker“ → „Anna Becker“, „Becker“ (für die Schreibkorrektur). */
    private fun participantTerms(names: List<String>): List<String> = names.flatMap { n ->
        val core = n.split(' ').filterNot { it.trimEnd('.').lowercase() in setOf("frau", "herr", "dr", "prof") }
            .joinToString(" ").trim()
        if (core.isEmpty()) emptyList() else listOf(core) + core.split(' ').drop(1).filter { it.length >= 4 }
    }.distinct()

    private fun summarize(id: String, set: de.protokollki.engine.ModelSet) {
        val meeting = MeetingRepository.get(id) ?: return
        if (meeting.segments.isEmpty()) return
        progress(id, MeetingStatus.SUMMARIZING, 0f, "Lade Sprachmodell …")
        de.protokollki.engine.AskEngine.releaseNow()   // nie zwei Sprachmodelle gleichzeitig im RAM
        val tSum = System.currentTimeMillis()
        val llm = LocalLlm(applicationContext, set)
        currentLlm = llm
        try {
            val gen = ProtocolGenerator(LocalLlmModel(llm))
            val candidates = if (AppSettings.state.value.trackTasks)
                de.protokollki.data.TaskTracker.candidatesFor(meeting) else emptyList()
            val openTasks = candidates.mapIndexed { i, (m, t) ->
                ProtocolGenerator.OpenTask("A${i + 1}", t.id, m.id, t.text, t.owner)
            }
            val result = gen.generate(
                title = meeting.title,
                dateMillis = meeting.createdAt,
                segments = meeting.segments,
                speakers = meeting.speakers,
                wantSummary = meeting.mode == ProtocolMode.ERGEBNIS,
                agenda = meeting.agenda,
                openTasks = openTasks,
                template = meeting.template,
                vocabulary = (participantTerms(meeting.participants) + de.protokollki.engine.Vocabulary.terms()).distinct(),
                participants = meeting.participants,
            ) { f, text ->
                progress(id, MeetingStatus.SUMMARIZING, f, text)
                !cancelled.get()
            }
            de.protokollki.engine.Timing.since("protokoll", tSum, "${result.tasks.size} Aufgaben")
            // Erledigte Aufgaben in den Ursprungs-Besprechungen abhaken
            val dateText = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMAN)
                .format(java.util.Date(meeting.createdAt))
            val doneIds = result.completed.map { it.taskId }.toSet()
            result.completed.forEach {
                de.protokollki.data.TaskTracker.setDone(it.meetingId, it.taskId, true,
                    "Erledigt laut Besprechung „${meeting.title}“ vom $dateText")
            }
            val followUps = openTasks.map {
                de.protokollki.data.FollowUp(it.taskId, it.meetingId, it.text, it.owner,
                    done = it.taskId in doneIds, ref = result.completedRefs[it.taskId] ?: -1)
            }
            MeetingRepository.update(id) {
                // Erledigt-Häkchen bei gleichem Aufgabentext beibehalten
                val doneTexts = it.tasks.filter { t -> t.done }.map { t -> t.text }.toSet()
                it.copy(
                    summary = if (meeting.mode == ProtocolMode.ERGEBNIS) result.summary else it.summary,
                    tasks = result.tasks.map { t -> if (t.text in doneTexts) t.copy(done = true) else t },
                    followUps = followUps,
                    sensitiveTerms = (it.sensitiveTerms + result.sensitiveTerms)
                        .distinctBy { t -> t.lowercase() },
                )
            }
        } finally {
            currentLlm = null
            llm.close()
        }
    }

    private var lastNotify = 0L

    private fun progress(id: String, status: MeetingStatus, f: Float, text: String) {
        MeetingRepository.update(id, persist = false) {
            it.copy(status = status, progress = f.coerceIn(0f, 1f), progressText = text)
        }
        val now = System.currentTimeMillis()
        if (now - lastNotify > 1000) {
            lastNotify = now
            runCatching { startInForeground(text, f) }
        }
        // Für die Emulator-Tests (lange Aufnahmen): Fortschritt höchstens alle 10 s ins Systemprotokoll
        if (now - lastLog > 10_000) {
            lastLog = now
            android.util.Log.i(TAG, "Fortschritt $id ${status.name} ${"%.3f".format(java.util.Locale.ROOT, f)} $text")
        }
    }

    private var lastLog = 0L

    private fun fail(id: String, message: String) {
        android.util.Log.w(TAG, "Fehlgeschlagen $id: $message")
        MeetingRepository.update(id) {
            it.copy(status = MeetingStatus.ERROR, error = message, progress = 0f, progressText = "")
        }
    }

    private fun abort(id: String) {
        if (timedOut) { fail(id, TIMEOUT_MESSAGE); return }
        MeetingRepository.update(id) {
            // Nur ein fertiges Protokoll (Neuerstellung abgebrochen) bleibt DONE. Ein Abbruch während
            // der Transkription hinterlässt sonst ein scheinbar fertiges, aber unvollständiges Protokoll.
            val back = if (it.segments.isNotEmpty() && it.speakers.isNotEmpty() && !it.summary.isEmpty) MeetingStatus.DONE
            else MeetingStatus.RECORDED
            it.copy(status = back, progress = 0f, progressText = "")
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(text: String, f: Float): Notification =
        NotificationCompat.Builder(this, ProtokollApp.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Protokoll wird erstellt")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (f * 100).toInt(), f <= 0f)
            .setContentIntent(contentIntent())
            .addAction(0, "Abbrechen", PendingIntent.getService(
                this, 1, Intent(this, ProcessingService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun startInForeground(text: String, f: Float) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text, f), type)
    }

    private fun notifyDone(id: String, title: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, ProtokollApp.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Protokoll fertig")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        runCatching { nm.notify(id.hashCode(), n) }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProtokollKI:processing").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val ACTION_PROCESS = "de.protokollki.PROCESS"
        private const val ACTION_REGENERATE = "de.protokollki.REGENERATE"
        private const val ACTION_CANCEL = "de.protokollki.CANCEL"
        private const val EXTRA_ID = "id"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "ProtokollKI-Verarbeitung"
        private const val TIMEOUT_MESSAGE =
            "Android hat die Hintergrundverarbeitung nach mehreren Stunden beendet (Zeitlimit des Systems). " +
                "Bitte das Handy ans Ladegerät anschließen, die App geöffnet lassen und erneut versuchen."

        fun process(context: Context, meetingId: String) = send(context, ACTION_PROCESS, meetingId)

        fun regenerate(context: Context, meetingId: String) =
            send(context, ACTION_REGENERATE, meetingId)

        fun cancel(context: Context) = send(context, ACTION_CANCEL, null)

        private fun send(context: Context, action: String, id: String?) {
            val i = Intent(context, ProcessingService::class.java).setAction(action)
            if (id != null) i.putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
