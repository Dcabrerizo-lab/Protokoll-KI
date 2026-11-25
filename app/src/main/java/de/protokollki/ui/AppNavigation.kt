package de.protokollki.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.protokollki.audio.importAudio
import de.protokollki.data.AppSettings
import de.protokollki.data.MeetingRepository
import de.protokollki.engine.ModelManager
import de.protokollki.importdoc.ProtocolImporter
import de.protokollki.service.ProcessingService
import de.protokollki.ui.components.applyTemplate
import de.protokollki.ui.screens.AskScreen
import de.protokollki.ui.screens.HomeScreen
import de.protokollki.ui.screens.LegalDoc
import de.protokollki.ui.screens.LegalScreen
import de.protokollki.ui.screens.MeetingScreen
import de.protokollki.ui.screens.OnboardingScreen
import de.protokollki.ui.screens.RecordingScreen
import de.protokollki.ui.screens.SettingsScreen
import de.protokollki.ui.screens.VoiceProfilesScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val RECORDING = "recording"
    const val SETTINGS = "settings"
    const val VOICES = "voices"
    const val ASK = "ask"
    const val LEGAL = "legal/{doc}"
    fun legal(doc: String) = "legal/$doc"
    const val MEETING = "meeting/{id}?tab={tab}&seg={seg}"
    /**
     * [tab]: optional „tasks“, „transcript“ oder „summary“ – öffnet direkt diesen Reiter.
     * [seg]: optional Index eines Redebeitrags – öffnet das Transkript an dieser Stelle und spielt ab.
     */
    fun meeting(id: String, tab: String? = null, seg: Int = -1): String {
        val params = buildList {
            if (tab != null) add("tab=$tab")
            if (seg >= 0) add("seg=$seg")
        }
        return if (params.isEmpty()) "meeting/$id" else "meeting/$id?" + params.joinToString("&")
    }
}

fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Öffnet die App-Info in den Systemeinstellungen (z. B. nach verweigerter Berechtigung). */
fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun AppNavigation(
    sharedUri: Uri?,
    onSharedUriHandled: () -> Unit,
) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val startDestination = remember {
        if (!AppSettings.state.value.onboardingDone || !ModelManager.state.value.installed) {
            Routes.ONBOARDING
        } else {
            Routes.HOME
        }
    }

    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    val importFile: (Uri) -> Unit = importFile@{ uri ->
        if (importing) return@importFile
        importing = true
        scope.launch {
            try {
                // Austauschdatei vom PC-Programm (fertiges Protokoll oder Aufnahme vom PC)
                if (de.protokollki.transfer.PcTransfer.isPackage(context, uri)) {
                    val meeting = de.protokollki.transfer.PcTransfer.import(context, uri)
                    nav.navigate(Routes.meeting(meeting.id)) { launchSingleTop = true }
                    return@launch
                }
                val isDoc = runCatching { ProtocolImporter.isDocument(context, uri) }.getOrDefault(false)
                val defaultTemplate = AppSettings.state.value.defaultTemplate
                if (isDoc) {
                    // Altes Protokoll (PDF/Word/Text): Text übernehmen, KI erstellt Zusammenfassung + Aufgaben
                    val meeting = ProtocolImporter.import(context, uri, startProcessing = false)
                    runCatching { applyTemplate(context, meeting.id, defaultTemplate) }
                    if (ModelManager.state.value.installed) ProcessingService.regenerate(context, meeting.id)
                    nav.navigate(Routes.meeting(meeting.id)) { launchSingleTop = true }
                    return@launch
                }
                val meeting = withContext(Dispatchers.Default) { importAudio(context, uri) }
                // Standardvorlage auch für importierte Dateien
                runCatching { applyTemplate(context, meeting.id, defaultTemplate) }
                de.protokollki.DebugHooks.consumeAgenda()?.let { agenda ->
                    MeetingRepository.update(meeting.id) { it.copy(agenda = agenda) }
                }
                if (AppSettings.state.value.autoProcess && ModelManager.state.value.installed) {
                    ProcessingService.process(context, meeting.id)
                }
                nav.navigate(Routes.meeting(meeting.id)) { launchSingleTop = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                importError = e.message ?: "Die Datei konnte nicht gelesen werden."
            } finally {
                importing = false
            }
        }
    }

    LaunchedEffect(sharedUri) {
        val uri = sharedUri ?: return@LaunchedEffect
        onSharedUriHandled()
        importFile(uri)
    }

    NavHost(
        navController = nav,
        startDestination = startDestination,
        enterTransition = { slideInHorizontally(initialOffsetX = { it / 6 }) + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { it / 6 }) + fadeOut() },
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    nav.navigate(Routes.HOME) {
                        popUpTo(nav.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenMeeting = { id -> nav.navigate(Routes.meeting(id)) { launchSingleTop = true } },
                onOpenTask = { id -> nav.navigate(Routes.meeting(id, "tasks")) { launchSingleTop = true } },
                onStartRecording = { nav.navigate(Routes.RECORDING) { launchSingleTop = true } },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onImport = importFile,
                onOpenAsk = { nav.navigate(Routes.ASK) { launchSingleTop = true } },
            )
        }
        composable(Routes.ASK) {
            AskScreen(
                onBack = { nav.popBackStack() },
                onOpenHit = { id, seg ->
                    if (seg >= 0) nav.navigate(Routes.meeting(id, "transcript", seg))
                    else nav.navigate(Routes.meeting(id, "summary"))
                },
            )
        }
        composable(Routes.RECORDING) {
            RecordingScreen(
                onFinished = { id ->
                    nav.navigate(Routes.meeting(id)) {
                        popUpTo(Routes.RECORDING) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = Routes.MEETING,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("tab") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("seg") {
                    type = NavType.IntType
                    defaultValue = -1
                },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val tab = entry.arguments?.getString("tab")
            val seg = entry.arguments?.getInt("seg", -1) ?: -1
            MeetingScreen(
                meetingId = id,
                initialTab = tab,
                initialSegment = seg,
                onBack = { nav.popBackStack() },
                // Sprung zu einer anderen (früheren) Besprechung: Zurück führt hierher zurück
                onOpenMeeting = { other -> if (other != id) nav.navigate(Routes.meeting(other, "tasks")) },
                onOpenRecording = { nav.navigate(Routes.RECORDING) { launchSingleTop = true } },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenVoices = { nav.navigate(Routes.VOICES) { launchSingleTop = true } },
                onOpenLegal = { doc -> nav.navigate(Routes.legal(doc.id)) { launchSingleTop = true } },
            )
        }
        composable(
            route = Routes.LEGAL,
            arguments = listOf(navArgument("doc") { type = NavType.StringType }),
        ) { entry ->
            LegalScreen(
                doc = LegalDoc.of(entry.arguments?.getString("doc")),
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.VOICES) {
            VoiceProfilesScreen(onBack = { nav.popBackStack() })
        }
    }

    if (importing) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp))
                    Text("Datei wird importiert …", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Die Datei wird für die Offline-Verarbeitung vorbereitet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    importError?.let { msg ->
        AlertDialog(
            onDismissRequest = { importError = null },
            icon = { Icon(Icons.Filled.ErrorOutline, contentDescription = null) },
            title = { Text("Import fehlgeschlagen") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } },
        )
    }
}
