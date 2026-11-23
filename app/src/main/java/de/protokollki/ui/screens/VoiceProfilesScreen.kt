package de.protokollki.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.protokollki.audio.Recorder
import de.protokollki.engine.VoiceProfiles
import de.protokollki.ui.components.EmptyState
import de.protokollki.ui.components.SpeakerAvatar
import de.protokollki.ui.components.formatDateShort
import de.protokollki.ui.components.recordVoiceSample
import de.protokollki.ui.hasPermission
import de.protokollki.ui.openAppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val ENROLL_MAX_MS = 16_000L
private const val ENROLL_MIN_MS = 9_000L

/** Vorlesetext für das Einlernen (ca. 15 Sekunden bei normalem Sprechtempo). */
private const val READING_TEXT =
    "Guten Tag! Ich lese diesen kurzen Text vor, damit die App meine Stimme kennenlernt. " +
        "In unseren Besprechungen geht es um Termine, Aufgaben und Beschlüsse. " +
        "Am Ende entsteht automatisch ein Protokoll, in dem jede Aufgabe der richtigen Person zugeordnet ist."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceProfilesScreen(onBack: () -> Unit) {
    val profiles by VoiceProfiles.profiles.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var enrolling by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text("Stimmprofile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { enrolling = true },
                icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                text = { Text("Neue Stimme einlernen") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "intro") { IntroCard() }
            if (profiles.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Filled.RecordVoiceOver,
                        title = "Noch keine Stimmen",
                        message = "Gespeicherte Stimmen werden in neuen Besprechungen automatisch mit Namen erkannt.",
                    )
                }
            } else {
                item(key = "header") {
                    Text(
                        if (profiles.size == 1) "1 gespeicherte Stimme" else "${profiles.size} gespeicherte Stimmen",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                    )
                }
                itemsIndexed(profiles, key = { i, _ -> "vp_$i" }) { i, p ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SpeakerAvatar(name = p.name, id = i, size = 40.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Seit ${formatDateShort(p.createdAt)} · " +
                                        if (p.samples == 1) "1 Stimmprobe" else "${p.samples} Stimmproben",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { renameId = p.id }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Umbenennen")
                            }
                            IconButton(onClick = { deleteId = p.id }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                            }
                        }
                    }
                }
            }
        }
    }

    if (enrolling) {
        EnrollDialog(onDismiss = { enrolling = false })
    }

    renameId?.let { id ->
        val p = profiles.firstOrNull { it.id == id }
        if (p == null) {
            renameId = null
        } else {
            TitleDialogLike(
                title = "Stimmprofil umbenennen",
                label = "Name",
                current = p.name,
                onDismiss = { renameId = null },
                onSave = { name ->
                    renameId = null
                    if (name.isNotBlank()) runCatching { VoiceProfiles.rename(id, name) }
                },
            )
        }
    }

    deleteId?.let { id ->
        val p = profiles.firstOrNull { it.id == id }
        if (p == null) {
            deleteId = null
        } else {
            AlertDialog(
                onDismissRequest = { deleteId = null },
                icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                title = { Text("Stimme von ${p.name} löschen?") },
                text = { Text("${p.name} wird in künftigen Besprechungen nicht mehr automatisch erkannt. Bestehende Protokolle bleiben unverändert.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteId = null
                            runCatching { VoiceProfiles.delete(id) }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Löschen") }
                },
                dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Abbrechen") } },
            )
        }
    }
}

@Composable
private fun IntroCard() {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cs.primaryContainer,
        contentColor = cs.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Teilnehmende automatisch erkennen", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            IntroStep(
                Icons.Filled.TouchApp,
                "Am schnellsten: In einer Besprechung auf eine Person tippen, Namen eingeben – " +
                    "„Stimme merken“ ist schon angehakt.",
            )
            Spacer(Modifier.height(8.dp))
            IntroStep(
                Icons.Filled.Mic,
                "Oder hier: Die Person liest 15 Sekunden einen kurzen Text vor.",
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Stimmprofile werden nur auf diesem Gerät gespeichert.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onPrimaryContainer.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun IntroStep(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TitleDialogLike(
    title: String,
    label: String,
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

// ================================================================ Einlernen

private enum class EnrollStep { READY, RECORDING, SAVING, DONE, ERROR }

/** Geführtes Einlernen: Name, Text vorlesen (max. 16 s, Pegelanzeige + Countdown), speichern. */
@Composable
private fun EnrollDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(EnrollStep.READY) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    var levels by remember { mutableStateOf(List(48) { 0f }) }
    var error by remember { mutableStateOf<String?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    val stopFlag = remember { AtomicBoolean(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    // Bildschirm während der Aufnahme anlassen
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            stopFlag.set(true)
        }
    }

    fun startRecording() {
        if (Recorder.state.value.active) {
            error = "Während einer laufenden Besprechungsaufnahme ist das Einlernen nicht möglich."
            step = EnrollStep.ERROR
            return
        }
        stopFlag.set(false)
        elapsed = 0L
        levels = List(48) { 0f }
        error = null
        step = EnrollStep.RECORDING
        val app = context.applicationContext
        val profileName = name.trim()
        job = scope.launch {
            try {
                val samples = recordVoiceSample(
                    maxMs = ENROLL_MAX_MS,
                    stopRequested = { stopFlag.get() },
                ) { lvl, ms ->
                    level = lvl
                    elapsed = ms
                    levels = (levels + lvl).takeLast(48)
                }
                if (stopFlag.get() && samples.size < 16_000 * 3) {
                    // abgebrochen
                    step = EnrollStep.READY
                    return@launch
                }
                step = EnrollStep.SAVING
                withContext(Dispatchers.Default) {
                    VoiceProfiles.enrollFromRecording(app, profileName, samples)
                }
                step = EnrollStep.DONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Die Stimme konnte nicht gespeichert werden."
                step = EnrollStep.ERROR
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startRecording() else permissionDenied = true }

    val requestStart: () -> Unit = {
        if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) startRecording()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val close: () -> Unit = {
        stopFlag.set(true)
        job?.cancel()
        onDismiss()
    }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = close) { Icon(Icons.Filled.Close, contentDescription = "Schließen") }
                    Text("Neue Stimme einlernen", style = MaterialTheme.typography.titleLarge)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (step) {
                        EnrollStep.READY, EnrollStep.RECORDING -> {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                enabled = step == EnrollStep.READY,
                                singleLine = true,
                                label = { Text("Name der Person") },
                                placeholder = { Text("z. B. Anna Schmidt") },
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Words,
                                    imeAction = ImeAction.Done,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(18.dp))
                            Text(
                                if (step == EnrollStep.RECORDING) "Jetzt bitte laut und deutlich vorlesen:"
                                else "Diesen Text gleich vorlesen:",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (step == EnrollStep.RECORDING) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    READING_TEXT,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Normal, lineHeight = 30.sp,
                                    ),
                                    modifier = Modifier.padding(18.dp),
                                )
                            }
                            Spacer(Modifier.height(24.dp))
                            if (step == EnrollStep.RECORDING) {
                                RecordingIndicator(elapsed = elapsed, levels = levels, level = level)
                            } else if (permissionDenied) {
                                Text(
                                    "Für das Einlernen braucht die App Zugriff auf das Mikrofon. " +
                                        "Die Aufnahme bleibt auf diesem Gerät.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                )
                                TextButton(onClick = { openAppSettings(context) }) { Text("App-Einstellungen öffnen") }
                            } else {
                                Text(
                                    "Ruhige Umgebung, Smartphone in normalem Gesprächsabstand.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        EnrollStep.SAVING -> {
                            Spacer(Modifier.height(80.dp))
                            CircularProgressIndicator(modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(20.dp))
                            Text("Stimmprofil wird erstellt …", style = MaterialTheme.typography.titleMedium)
                        }
                        EnrollStep.DONE -> {
                            Spacer(Modifier.height(64.dp))
                            Icon(
                                Icons.Filled.CheckCircle, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(72.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Stimme von ${name.trim()} gespeichert",
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${name.trim()} wird in neuen Besprechungen automatisch erkannt.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                        EnrollStep.ERROR -> {
                            Spacer(Modifier.height(64.dp))
                            Icon(
                                Icons.Filled.ErrorOutline, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(64.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Das hat nicht geklappt",
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                error ?: "Bitte noch einmal versuchen.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                // Untere Aktion
                Box(Modifier.fillMaxWidth().padding(24.dp)) {
                    when (step) {
                        EnrollStep.READY -> Button(
                            onClick = requestStart,
                            enabled = name.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (name.isBlank()) "Erst Namen eingeben" else "Aufnahme starten")
                        }
                        EnrollStep.RECORDING -> Button(
                            onClick = { stopFlag.set(true) },
                            enabled = elapsed >= ENROLL_MIN_MS,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (elapsed >= ENROLL_MIN_MS) "Fertig" else "Bitte weiterlesen …")
                        }
                        EnrollStep.SAVING -> Unit
                        EnrollStep.DONE -> Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("Fertig") }
                        EnrollStep.ERROR -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { step = EnrollStep.READY },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text("Nochmal versuchen") }
                            OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
                        }
                    }
                }
            }
        }
    }
}

/** Countdown-Ring + Pegelanzeige während des Einlernens. */
@Composable
private fun RecordingIndicator(elapsed: Long, levels: List<Float>, level: Float) {
    val cs = MaterialTheme.colorScheme
    val remaining = ((ENROLL_MAX_MS - elapsed).coerceAtLeast(0L) + 999L) / 1000L
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { (elapsed.toFloat() / ENROLL_MAX_MS).coerceIn(0f, 1f) },
                modifier = Modifier.size(96.dp),
                strokeWidth = 6.dp,
                trackColor = cs.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$remaining", style = MaterialTheme.typography.headlineMedium)
                Text("Sek.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        val barColor = cs.primary
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val n = levels.size.coerceAtLeast(1)
            val step = size.width / n
            val barW = (step * 0.6f).coerceAtLeast(2f)
            val midY = size.height / 2f
            levels.forEachIndexed { i, raw ->
                val h = (raw.coerceIn(0f, 1f) * size.height).coerceAtLeast(3.dp.toPx())
                drawRoundRect(
                    color = barColor.copy(alpha = 0.35f + 0.65f * (i.toFloat() / n)),
                    topLeft = Offset(i * step + (step - barW) / 2f, midY - h / 2f),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                level < 0.15f -> "Etwas lauter sprechen"
                level > 0.95f -> "Etwas leiser oder weiter weg"
                else -> "Pegel gut"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (level < 0.15f || level > 0.95f) cs.tertiary else cs.primary,
        )
    }
}
