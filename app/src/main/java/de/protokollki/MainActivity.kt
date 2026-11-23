package de.protokollki

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import de.protokollki.ui.AppNavigation
import de.protokollki.ui.theme.ProtokollTheme

/**
 * Einziger Einstieg der App. Berechtigungen werden dort angefragt, wo sie gebraucht werden:
 * Mikrofon (+ Benachrichtigungen ab Android 13) beim ersten Tippen auf „Aufnehmen“,
 * Benachrichtigungen außerdem vor dem Modell-Download im Onboarding.
 */
class MainActivity : ComponentActivity() {

    /** Per „Teilen“ empfangene Audio-/Videodatei, die importiert werden soll. */
    private val sharedUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            ProtokollTheme {
                AppNavigation(
                    sharedUri = sharedUri.value,
                    onSharedUriHandled = { sharedUri.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        val type = intent.type.orEmpty()
        if (!type.startsWith("audio/") && !type.startsWith("video/")) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (uri != null) sharedUri.value = uri
    }
}
