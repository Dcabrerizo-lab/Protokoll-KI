package de.protokollki

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import de.protokollki.data.AppSettings
import de.protokollki.privacy.AppLock
import de.protokollki.privacy.Retention
import de.protokollki.ui.AppNavigation
import de.protokollki.ui.components.CrashDialog
import de.protokollki.ui.components.LockOverlay
import de.protokollki.ui.theme.ProtokollTheme
import kotlinx.coroutines.delay

/**
 * Einziger Einstieg der App. Berechtigungen werden dort angefragt, wo sie gebraucht werden:
 * Mikrofon (+ Benachrichtigungen ab Android 13) beim ersten Tippen auf „Aufnehmen“,
 * Benachrichtigungen außerdem vor dem Modell-Download im Onboarding.
 *
 * Datenschutz: FLAG_SECURE folgt der Einstellung „Screenshots blockieren“; die App-Sperre legt
 * eine vollflächige Sperre über alle Inhalte, solange [AppLock.locked] gilt.
 */
class MainActivity : ComponentActivity() {

    /** Per „Teilen“ empfangene Audio-/Videodatei, die importiert werden soll. */
    private val sharedUri = mutableStateOf<Uri?>(null)

    /** Bericht vom letzten Absturz, falls einer vorliegt. */
    private val crashReport = mutableStateOf<String?>(null)

    /** Letzte Fehlermeldung beim Entsperren (wird auf der Sperre angezeigt). */
    private val unlockError = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applySecureFlag(AppSettings.state.value.blockScreenshots)
        if (savedInstanceState == null) {
            handleIntent(intent)
            // Löschfrist für Audio anwenden (abseits des Main-Threads)
            val app = applicationContext
            Thread({ runCatching { Retention.apply(app) } }, "retention").apply {
                isDaemon = true
                start()
            }
        }
        // Bericht vom letzten Absturz (nur lokal gespeichert); in Test-Builds nicht anzeigen,
        // damit automatische Oberflächentests nicht an einem Dialog hängen bleiben.
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (savedInstanceState == null && !debuggable) {
            crashReport.value = runCatching { CrashReporter.takePending(this) }.getOrNull()
        }
        setContent {
            val settings by AppSettings.state.collectAsState()
            val locked by AppLock.locked.collectAsState()
            LaunchedEffect(settings.blockScreenshots) { applySecureFlag(settings.blockScreenshots) }

            val lockAvailable = remember(settings.appLock, locked) {
                runCatching { AppLock.isAvailable(this@MainActivity) }.getOrDefault(false)
            }
            val showLock = settings.appLock && locked && lockAvailable

            ProtokollTheme {
                Box(Modifier.fillMaxSize()) {
                    AppNavigation(
                        sharedUri = sharedUri.value,
                        onSharedUriHandled = { sharedUri.value = null },
                    )
                    if (showLock) {
                        // Zurück verlässt die App, statt an der Sperre vorbei zu navigieren
                        BackHandler { moveTaskToBack(true) }
                        LaunchedEffect(Unit) {
                            unlockError.value = null
                            delay(350)
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) unlock()
                        }
                        LockOverlay(onUnlock = { unlock() }, error = unlockError.value)
                    }
                    crashReport.value?.takeIf { !showLock }?.let { report ->
                        CrashDialog(
                            onSend = {
                                crashReport.value = null
                                CrashReporter.share(this@MainActivity, report)
                            },
                            onDismiss = { crashReport.value = null },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { AppLock.onAppForegrounded() }
    }

    override fun onStop() {
        super.onStop()
        runCatching { AppLock.onAppBackgrounded() }
    }

    private fun unlock() {
        unlockError.value = null
        runCatching {
            AppLock.authenticate(
                this,
                { runOnUiThread { unlockError.value = null } },
                { msg -> runOnUiThread { unlockError.value = msg } },
            )
        }.onFailure { e ->
            unlockError.value = e.message ?: "Entsperren nicht möglich."
        }
    }

    private fun applySecureFlag(block: Boolean) {
        if (block) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // Nur in Test-Builds (debuggable): automatischer Ende-zu-Ende-Test im Emulator
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && intent.action == "de.protokollki.DEBUG_IMPORT") {
            intent.getStringExtra("model_set")?.let { de.protokollki.engine.ModelManager.selectSet(it) }
            val template = de.protokollki.data.ProtocolTemplate.of(intent.getStringExtra("template"))
            de.protokollki.data.AppSettings.update {
                val vocab = intent.getStringExtra("vocabulary")
                    ?.split("|")?.map { v -> v.trim() }?.filter { v -> v.isNotEmpty() }
                it.copy(onboardingDone = true, autoProcess = true, defaultTemplate = template,
                    vocabulary = vocab ?: it.vocabulary)
            }
            DebugHooks.pendingAgenda = intent.getStringExtra("agenda")
                ?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
            intent.getStringExtra("path")?.let { sharedUri.value = Uri.fromFile(java.io.File(it)) }
            return
        }
        val type = intent.type.orEmpty()
        // .protokoll-Datei vom PC geöffnet (Dateimanager) oder geteilt
        val isPcFile = type == "application/octet-stream" || type == "application/zip"
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { sharedUri.value = it }
            return
        }
        if (intent.action != Intent.ACTION_SEND) return
        if (isPcFile) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { sharedUri.value = it }
            return
        }
        val isDoc = runCatching { de.protokollki.importdoc.ProtocolImporter.isDocument(type, null) }
            .getOrDefault(false)
        if (!type.startsWith("audio/") && !type.startsWith("video/") && !isDoc) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (uri != null) sharedUri.value = uri
    }
}
