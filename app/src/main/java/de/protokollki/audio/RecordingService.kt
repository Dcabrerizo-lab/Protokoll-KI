package de.protokollki.audio

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import de.protokollki.MainActivity
import de.protokollki.ProtokollApp
import de.protokollki.R
import de.protokollki.data.MeetingRepository

/**
 * Vordergrunddienst (Typ „microphone“) für die Aufnahme. Zeigt eine laufende Benachrichtigung
 * mit Zeit sowie Pause/Fortsetzen und Beenden, hält einen Partial-WakeLock.
 * Die eigentliche Aufnahme erledigt [Recorder].
 */
class RecordingService : Service() {

    companion object {
        const val EXTRA_MEETING_ID = "meetingId"
        const val ACTION_PAUSE = "de.protokollki.audio.PAUSE"
        const val ACTION_RESUME = "de.protokollki.audio.RESUME"
        const val ACTION_STOP = "de.protokollki.audio.STOP"
        private const val NOTIFICATION_ID = 4201
        private const val TAG = "RecordingService"
        private const val WAKELOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000 // 6 h Sicherheitsgrenze
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var ticking = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!Recorder.state.value.active) {
                ticking = false
                return
            }
            updateNotification()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                Recorder.pause()
                updateNotification()
                if (!foreground) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                Recorder.resume()
                updateNotification()
                if (!foreground) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                Recorder.stop(this)
                if (!foreground) stopSelf()
                return START_NOT_STICKY
            }
        }

        // Start über startForegroundService: startForeground muss in jedem Fall erfolgen.
        if (!goForeground()) {
            Recorder.failStart(
                "Die Aufnahme konnte nicht gestartet werden. Bitte die App geöffnet lassen " +
                    "und den Mikrofonzugriff erlauben.",
            )
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Recorder.beginCapture(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (!ticking) {
            ticking = true
            handler.post(ticker)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        ticking = false
        releaseWakeLock()
        Recorder.onServiceDestroyed(applicationContext)
        if (foreground) {
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            foreground = false
        }
        super.onDestroy()
    }

    // -------------------------------------------------------------------------------------

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        foreground = true
        true
    } catch (e: Exception) {
        // z. B. ForegroundServiceStartNotAllowedException oder SecurityException (Berechtigung)
        Log.e(TAG, "startForeground fehlgeschlagen", e)
        false
    }

    private fun updateNotification() {
        if (!foreground) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // Benachrichtigungen evtl. nicht erlaubt – Aufnahme läuft trotzdem weiter
        }
    }

    private fun buildNotification(): Notification {
        val s = Recorder.state.value
        val time = formatHms(s.elapsedMs)
        val title = if (s.paused) "Aufnahme pausiert · $time" else "Aufnahme läuft · $time"
        val meetingTitle = s.meetingId?.let { MeetingRepository.get(it)?.title } ?: "Protokoll KI"

        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            immutable,
        )
        val toggleAction = if (s.paused) ACTION_RESUME else ACTION_PAUSE
        val togglePi = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(toggleAction), immutable,
        )
        val stopPi = PendingIntent.getService(
            this, 2, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), immutable,
        )

        return NotificationCompat.Builder(this, ProtokollApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(meetingTitle)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (s.paused) "Fortsetzen" else "Pausieren", togglePi)
            .addAction(0, "Beenden", stopPi)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProtokollKI:Aufnahme").apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock nicht verfügbar", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignorieren
        }
        wakeLock = null
    }
}
