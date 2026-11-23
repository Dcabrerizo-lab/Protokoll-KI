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
            .apply()
    }
}
