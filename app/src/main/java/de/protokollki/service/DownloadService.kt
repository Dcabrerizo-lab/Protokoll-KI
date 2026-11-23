package de.protokollki.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
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
import de.protokollki.engine.ModelManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Einmaliger Download der KI-Modelle im Hintergrund. */
class DownloadService : LifecycleService() {
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startInForeground("Bereite Download vor …", 0f)
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            stopNow()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        acquireLocks()
        val watcher = lifecycleScope.launch {
            var last = 0L
            ModelManager.state.collectLatest { s ->
                val now = System.currentTimeMillis()
                if (s.downloading && now - last > 1000) {
                    last = now
                    startInForeground(s.status.ifEmpty { "Lade Modelle …" }, s.progress)
                }
            }
        }
        job = lifecycleScope.launch {
            try {
                ModelManager.downloadAll()
            } finally {
                watcher.cancel()
                stopNow()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopNow() {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground(text: String, f: Float) {
        val n = NotificationCompat.Builder(this, ProtokollApp.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("KI-Modelle werden geladen")
            .setContentText("$text ${(f * 100).toInt()} %")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (f * 100).toInt(), f <= 0f)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Abbrechen", PendingIntent.getService(this, 2,
                Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE))
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProtokollKI:download")
            .apply { setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L) }
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ProtokollKI:download")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null; wifiLock = null
    }

    companion object {
        private const val ACTION_START = "de.protokollki.DOWNLOAD"
        private const val ACTION_CANCEL = "de.protokollki.DOWNLOAD_CANCEL"
        private const val NOTIFICATION_ID = 43

        fun start(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, DownloadService::class.java).setAction(ACTION_START))

        fun cancel(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL))
    }
}
