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

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_WORK = "work"
        const val CHANNEL_DONE = "done"
    }
}
