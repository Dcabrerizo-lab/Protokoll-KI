package de.protokollki

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import de.protokollki.data.AppSettings
import de.protokollki.data.MeetingRepository
import de.protokollki.engine.ModelManager

class ProtokollApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        MeetingRepository.init(this)
        ModelManager.init(this)
        de.protokollki.engine.VoiceProfiles.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Aufnahme", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Laufende Aufnahmen"; setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WORK, "Verarbeitung", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Transkription, Protokoll und Modell-Download" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Fertige Protokolle", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Hinweis, wenn ein Protokoll fertig ist" }
        )
    }

    /**
     * Speicherdruck (z. B. `adb shell am send-trim-memory`): das ungenutzte Frage-Sprachmodell
     * (mehrere GB) freigeben, statt vom System beendet zu werden. Eine laufende Antwort und die
     * Verarbeitung im Vordergrunddienst bleiben unberührt.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            runCatching {
                if (!de.protokollki.engine.AskEngine.state.value.answering) de.protokollki.engine.AskEngine.release()
            }
        }
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_WORK = "work"
        const val CHANNEL_DONE = "done"
    }
}
