package de.protokollki.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    val defaultMode: ProtocolMode = ProtocolMode.ERGEBNIS,
    val autoProcess: Boolean = true,        // nach Aufnahme automatisch verarbeiten
    val language: String = "de",
    val modelSetId: String? = null,          // null = automatisch nach Gerät
    val onboardingDone: Boolean = false,
    // Datenschutz
    val appLock: Boolean = false,            // Sperre per Fingerabdruck/Geräte-PIN
    val blockScreenshots: Boolean = false,   // FLAG_SECURE: keine Screenshots, verdeckt in "Zuletzt"
    val audioRetentionDays: Int = 0,         // 0 = Audio behalten, sonst nach X Tagen löschen
    val customTerms: List<String> = emptyList(), // eigene Begriffe, die immer geschwärzt werden
    val trackTasks: Boolean = true,          // offene Aufgaben in Folgebesprechungen abgleichen
    val defaultTemplate: ProtocolTemplate = ProtocolTemplate.ALLGEMEIN,
    val vocabulary: List<String> = emptyList(), // eigene Fachbegriffe/Namen für bessere Erkennung
    val organization: String = "",           // Name der Einrichtung (Kopfzeile formaler Protokolle)
)

object AppSettings {
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _state.value = SettingsState(
            defaultMode = runCatching {
                ProtocolMode.valueOf(prefs.getString("defaultMode", null) ?: "ERGEBNIS")
            }.getOrDefault(ProtocolMode.ERGEBNIS),
            autoProcess = prefs.getBoolean("autoProcess", true),
            language = prefs.getString("language", "de") ?: "de",
            modelSetId = prefs.getString("modelSetId", null),
            onboardingDone = prefs.getBoolean("onboardingDone", false),
            appLock = prefs.getBoolean("appLock", false),
            blockScreenshots = prefs.getBoolean("blockScreenshots", false),
            audioRetentionDays = prefs.getInt("audioRetentionDays", 0),
            customTerms = (prefs.getString("customTerms", "") ?: "").split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() },
            trackTasks = prefs.getBoolean("trackTasks", true),
            defaultTemplate = ProtocolTemplate.of(prefs.getString("defaultTemplate", null)),
            vocabulary = (prefs.getString("vocabulary", "") ?: "").split("\n")
                .map { it.trim() }.filter { it.isNotEmpty() },
            organization = prefs.getString("organization", "") ?: "",
        )
    }

    fun update(change: (SettingsState) -> SettingsState) {
        val s = change(_state.value)
        _state.value = s
        prefs.edit()
            .putString("defaultMode", s.defaultMode.name)
            .putBoolean("autoProcess", s.autoProcess)
            .putString("language", s.language)
            .putString("modelSetId", s.modelSetId)
            .putBoolean("onboardingDone", s.onboardingDone)
            .putBoolean("appLock", s.appLock)
            .putBoolean("blockScreenshots", s.blockScreenshots)
            .putInt("audioRetentionDays", s.audioRetentionDays)
            .putString("customTerms", s.customTerms.joinToString("\n"))
            .putBoolean("trackTasks", s.trackTasks)
            .putString("defaultTemplate", s.defaultTemplate.name)
            .putString("vocabulary", s.vocabulary.joinToString("\n"))
            .putString("organization", s.organization)
            .apply()
    }
}
