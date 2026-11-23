package de.protokollki.privacy

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import de.protokollki.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-Sperre mit Fingerabdruck/Gesicht oder Geräte-PIN (framework [BiometricPrompt], ohne
 * zusätzliche Bibliothek).
 *
 * Ablauf: Ist `SettingsState.appLock` an, startet die App gesperrt ([locked] = true). Die UI zeigt
 * dann einen Sperrbildschirm und ruft [authenticate] auf. Nach mehr als 60 s im Hintergrund wird
 * erneut gesperrt ([onAppBackgrounded]/[onAppForegrounded], z. B. aus `ProcessLifecycleOwner` oder
 * `MainActivity.onStop/onStart`).
 */
object AppLock {
    /** Nach so langer Zeit im Hintergrund wird wieder gesperrt. */
    const val TIMEOUT_MS = 60_000L

    private val _locked = MutableStateFlow(AppSettings.state.value.appLock)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAt = 0L          // SystemClock.elapsedRealtime(), 0 = im Vordergrund
    private var cancel: CancellationSignal? = null
    private var promptStartedAt = 0L

    /** Gibt es eine Displaysperre (PIN, Muster, Passwort, ggf. Biometrie)? Sonst ist keine Sperre möglich. */
    fun isAvailable(context: Context): Boolean {
        val km = context.getSystemService(KeyguardManager::class.java)
        if (km?.isDeviceSecure == true) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bm = context.getSystemService(BiometricManager::class.java)
            return bm?.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
        }
        return false
    }

    fun onAppBackgrounded() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun onAppForegrounded() {
        val since = backgroundedAt
        backgroundedAt = 0L
        if (!AppSettings.state.value.appLock) {
            _locked.value = false
            return
        }
        if (since > 0L && SystemClock.elapsedRealtime() - since > TIMEOUT_MS) _locked.value = true
    }

    /** Sofort sperren (z. B. für eine Schaltfläche „Jetzt sperren“); wirkt nur bei eingeschalteter Sperre. */
    fun lockNow() {
        if (AppSettings.state.value.appLock) _locked.value = true
    }

    /**
     * Zeigt die System-Abfrage. [onSuccess] → entsperrt. [onFail] mit deutscher Meldung bei Abbruch
     * oder Fehler (einzelne Fehlversuche wiederholt das System selbst). Ist gar keine Displaysperre
     * eingerichtet, wird entsperrt (sonst käme man nie wieder in die App) und [onFail] erklärt warum.
     */
    fun authenticate(activity: Activity, onSuccess: () -> Unit, onFail: (String) -> Unit) {
        // Abfrage läuft schon (z. B. doppelter Aufruf bei Recomposition) – eine hängengebliebene
        // (Activity neu erstellt, Rückruf kam nie) nach 30 s verwerfen
        val running = cancel
        if (running != null) {
            if (SystemClock.elapsedRealtime() - promptStartedAt < 30_000L) return
            runCatching { running.cancel() }
            cancel = null
        }
        if (!isAvailable(activity)) {
            _locked.value = false
            onFail("Keine Displaysperre eingerichtet. Bitte in den Android-Einstellungen eine PIN, " +
                "ein Muster oder einen Fingerabdruck einrichten, damit die App-Sperre wirkt.")
            return
        }
        val builder = BiometricPrompt.Builder(activity)
            .setTitle("Protokoll KI entsperren")
            .setSubtitle("Mit Fingerabdruck, Gesicht oder Geräte-PIN")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(AUTHENTICATORS)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setConfirmationRequired(false)

        val signal = CancellationSignal()
        cancel = signal
        promptStartedAt = SystemClock.elapsedRealtime()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                if (cancel === signal) cancel = null
                _locked.value = false
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                if (cancel === signal) cancel = null
                onFail(errorMessage(errorCode, errString))
            }
            // onAuthenticationFailed: einzelner Fehlversuch – das System fragt selbst erneut.
        }
        try {
            builder.build().authenticate(signal, activity.mainExecutor, callback)
        } catch (e: Exception) {
            cancel = null
            onFail("Entsperren nicht möglich: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private val AUTHENTICATORS: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else 0

    private fun errorMessage(code: Int, raw: CharSequence?): String = when (code) {
        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
        BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> "Entsperren abgebrochen."
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT -> "Zu viele Versuche. Bitte kurz warten und erneut versuchen."
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> "Biometrie gesperrt. Bitte mit Geräte-PIN entsperren."
        BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT -> "Zeit abgelaufen. Bitte erneut versuchen."
        else -> raw?.toString()?.takeIf { it.isNotBlank() } ?: "Entsperren fehlgeschlagen."
    }
}
