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
import de.protokollki.audio.WavIO
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
    @Volatile private var currentLlm: LocalLlm? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var workerStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startInForeground("Protokoll KI arbeitet …", 0f)
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelled.set(true)
                currentLlm?.cancel()
            }
            ACTION_PROCESS, ACTION_REGENERATE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                synchronized(queued) {
                    if (id in queued) return START_NOT_STICKY
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

    private suspend fun worker() {
        acquireWakeLock()
        try {
            while (true) {
                val job = queue.tryReceive().getOrNull() ?: break
                cancelled.set(false)
                withContext(Dispatchers.Default) { run(job) }
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
            fail(id, "Zu wenig Arbeitsspeicher. Bitte andere Apps schließen oder ein kleineres Modellpaket wählen.")
        } catch (e: Throwable) {
            fail(id, "Verarbeitung fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun transcribe(id: String, expectedSpeakers: Int, set: de.protokollki.engine.ModelSet) {
        progress(id, MeetingStatus.TRANSCRIBING, 0f, "Lade Aufnahme …")
        val meeting = MeetingRepository.get(id) ?: return
        val samples = WavIO.readMono16k(meeting.audioFile)
        if (samples.size < 16000) throw IllegalStateException("Die Aufnahme ist zu kurz.")
        val duration = samples.size / 16000f
        MeetingRepository.update(id) { it.copy(durationSec = duration) }

        val transcriber = Transcriber(set, AppSettings.state.value.language)
        progress(id, MeetingStatus.TRANSCRIBING, 0.01f, "Erkenne Sprecher …")
        val turns = transcriber.diarize(samples, expectedSpeakers) { f ->
            progress(id, MeetingStatus.TRANSCRIBING, 0.2f * f, "Erkenne Sprecher …")
            !cancelled.get()
        }
        if (cancelled.get()) return
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

        val vocab = de.protokollki.engine.Vocabulary.terms()
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
        // Eigenes Wörterbuch: Namen und Fachbegriffe richtig schreiben
        val segments = if (vocab.isEmpty()) raw
        else raw.map { it.copy(text = de.protokollki.engine.Vocabulary.correct(it.text, vocab)) }
        // Nur Sprecher behalten, die tatsächlich Text haben
        val used = segments.map { it.speakerId }.toSet()
        MeetingRepository.update(id) {
            it.copy(segments = segments, speakers = speakers.filter { s -> s.id in used })
        }
        if (segments.isEmpty()) throw IllegalStateException("In der Aufnahme wurde keine Sprache erkannt.")
    }

    private fun summarize(id: String, set: de.protokollki.engine.ModelSet) {
        val meeting = MeetingRepository.get(id) ?: return
        if (meeting.segments.isEmpty()) return
        progress(id, MeetingStatus.SUMMARIZING, 0f, "Lade Sprachmodell …")
        de.protokollki.engine.AskEngine.releaseNow()   // nie zwei Sprachmodelle gleichzeitig im RAM
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
                vocabulary = de.protokollki.engine.Vocabulary.terms(),
            ) { f, text ->
                progress(id, MeetingStatus.SUMMARIZING, f, text)
                !cancelled.get()
            }
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
            startInForeground(text, f)
        }
    }

    private fun fail(id: String, message: String) {
        MeetingRepository.update(id) {
            it.copy(status = MeetingStatus.ERROR, error = message, progress = 0f, progressText = "")
        }
    }

    private fun abort(id: String) {
        MeetingRepository.update(id) {
            val back = if (it.segments.isNotEmpty() && it.speakers.isNotEmpty()) MeetingStatus.DONE
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
