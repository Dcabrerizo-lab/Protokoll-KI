package de.protokollki.ui.screens

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.protokollki.audio.Recorder
import de.protokollki.data.Meeting
import de.protokollki.data.MeetingRepository
import de.protokollki.data.MeetingStatus
import de.protokollki.data.ProtocolMode
import de.protokollki.data.TaskItem
import de.protokollki.data.TaskTracker
import de.protokollki.engine.ModelManager
import de.protokollki.importdoc.ProtocolImporter
import de.protokollki.service.ProcessingService
import de.protokollki.ui.components.EmptyState
import de.protokollki.ui.components.MetaItem
import de.protokollki.ui.components.OfflineBadge
import de.protokollki.ui.components.ReadAloudBar
import de.protokollki.ui.components.SpeakerAvatarStack
import de.protokollki.ui.components.StatusChip
import de.protokollki.ui.components.dayKey
import de.protokollki.ui.components.formatClock
import de.protokollki.ui.components.formatDayHeader
import de.protokollki.ui.components.formatDurationWords
import de.protokollki.ui.components.formatTime
import de.protokollki.ui.components.speakersOf
import de.protokollki.ui.hasPermission
import de.protokollki.ui.openAppSettings
import de.protokollki.ui.theme.RecordGradientEnd
import de.protokollki.ui.theme.RecordGradientStart
import de.protokollki.ui.theme.RecordRed
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onOpenMeeting: (String) -> Unit,
    onStartRecording: () -> Unit,
    onOpenSettings: () -> Unit,
    onImport: (Uri) -> Unit,
    onOpenTask: (String) -> Unit = onOpenMeeting,
    onOpenAsk: () -> Unit = {},
) {
    val context = LocalContext.current
    val meetings by MeetingRepository.meetings.collectAsStateWithLifecycle()
    val rec by Recorder.state.collectAsStateWithLifecycle()
    val ms by remember { ModelManager.state }.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<Meeting?>(null) }
    var micDenied by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }   // 0 = Besprechungen, 1 = Aufgaben
    var showDone by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val openTasks = remember(meetings) {
        runCatching { TaskTracker.openTasks() }.getOrDefault(emptyList())
    }
    val doneTasks = remember(meetings) {
        meetings.flatMap { m -> m.tasks.filter { it.done }.map { m to it } }.take(200)
    }
    val toggleTask: (Meeting, TaskItem) -> Unit = { m, t ->
        val nowDone = !t.done
        TaskTracker.setDone(m.id, t.id, nowDone)
        if (nowDone) {
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val r = snackbar.showSnackbar(
                    message = "Erledigt: ${t.text.take(40)}",
                    actionLabel = "Rückgängig",
                    duration = SnackbarDuration.Short,
                )
                if (r == SnackbarResult.ActionPerformed) TaskTracker.setDone(m.id, t.id, false)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.RECORD_AUDIO] == true ||
            hasPermission(context, Manifest.permission.RECORD_AUDIO)
        if (granted) onStartRecording() else micDenied = true
    }
    val startRecording: () -> Unit = {
        if (rec.active || hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
            onStartRecording()
        } else {
            val perms = if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                arrayOf(Manifest.permission.RECORD_AUDIO)
            }
            permissionLauncher.launch(perms)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImport(uri) }
    val startImport: () -> Unit = {
        importLauncher.launch(arrayOf("audio/*", "video/*") + ProtocolImporter.MIME_TYPES +
            arrayOf("application/octet-stream", "application/zip"))   // .protokoll vom PC
    }

    val groups = remember(meetings) { meetings.groupBy { dayKey(it.createdAt) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            HomeTopBar(
                count = meetings.size,
                openTaskCount = openTasks.size,
                tab = tab,
                onTab = { tab = it },
                showTabs = meetings.isNotEmpty(),
                onOpenSettings = onOpenSettings,
            )
        },
        bottomBar = {
            Column {
                ReadAloudBar()
                BottomDock(
                    recordingActive = rec.active,
                    onRecord = startRecording,
                    onImport = startImport,
                    onSettings = onOpenSettings,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (meetings.isNotEmpty() && tab == 0) {
                item(key = "search") {
                    AskEntry(onClick = onOpenAsk)
                }
            }
            if (rec.active) {
                item(key = "recBanner") {
                    RecordingBanner(
                        elapsedMs = rec.elapsedMs,
                        paused = rec.paused,
                        onClick = onStartRecording,
                    )
                }
            }
            if (!ms.installed && tab == 0) {
                item(key = "modelBanner") {
                    ModelBanner(
                        downloading = ms.downloading,
                        progress = ms.progress,
                        status = ms.status,
                        onClick = onOpenSettings,
                    )
                }
            }

            if (tab == 1 && meetings.isNotEmpty()) {
                allTasksItems(
                    open = openTasks,
                    done = doneTasks,
                    showDone = showDone,
                    onShowDoneChange = { showDone = it },
                    onToggle = toggleTask,
                    onOpen = { m -> onOpenTask(m.id) },
                )
            } else if (meetings.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Filled.Mic,
                        title = "Noch keine Besprechungen",
                        message = "Tippen Sie unten auf den Aufnahme-Knopf, um Ihre erste Besprechung " +
                            "aufzuzeichnen – oder importieren Sie eine Aufnahme oder alte Protokolle, " +
                            "damit sie durchsuchbar werden.",
                        modifier = Modifier.padding(top = 48.dp),
                        action = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                FilledTonalButton(onClick = startImport) {
                                    Icon(Icons.Filled.UploadFile, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Datei importieren")
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Audio, Video oder altes Protokoll (PDF, Word, Text)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
            } else {
                groups.forEach { (day, list) ->
                    item(key = "day_$day") {
                        Text(
                            formatDayHeader(list.first().createdAt),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(list, key = { it.id }) { m ->
                        SwipeableMeetingCard(
                            meeting = m,
                            onClick = { onOpenMeeting(m.id) },
                            onDeleteRequest = { pendingDelete = m },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { m ->
        val recordingThis = rec.active && rec.meetingId == m.id
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Besprechung löschen?") },
            text = {
                Text(
                    if (recordingThis) "Diese Besprechung wird gerade aufgenommen. Beenden Sie zuerst die Aufnahme."
                    else "„${m.title}“ wird mit Aufnahme, Transkript und Protokoll endgültig gelöscht."
                )
            },
            confirmButton = {
                if (!recordingThis) {
                    TextButton(
                        onClick = {
                            if (m.isBusy) ProcessingService.cancel(context)
                            MeetingRepository.delete(m.id)
                            pendingDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                    ) { Text("Löschen") }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(if (recordingThis) "OK" else "Abbrechen")
                }
            },
        )
    }

    if (micDenied) {
        AlertDialog(
            onDismissRequest = { micDenied = false },
            icon = { Icon(Icons.Filled.MicOff, contentDescription = null) },
            title = { Text("Mikrofon-Zugriff benötigt") },
            text = {
                Text(
                    "Für Aufnahmen braucht Protokoll KI Zugriff auf das Mikrofon. " +
                        "Die Aufnahme bleibt ausschließlich auf Ihrem Gerät."
                )
            },
            confirmButton = {
                TextButton(onClick = { micDenied = false; openAppSettings(context) }) {
                    Text("Einstellungen öffnen")
                }
            },
            dismissButton = {
                TextButton(onClick = { micDenied = false }) { Text("Abbrechen") }
            },
        )
    }
}

// ---------------------------------------------------------------- Kopfzeile & Suche

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    count: Int,
    openTaskCount: Int,
    tab: Int,
    onTab: (Int) -> Unit,
    showTabs: Boolean,
    onOpenSettings: () -> Unit,
) {
  Column(
      modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.background)
          .statusBarsPadding(),
  ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Protokoll KI", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OfflineBadge(label = "Offline · privat")
                Spacer(Modifier.width(8.dp))
                Text(
                    when (count) {
                        0 -> "Keine Besprechungen"
                        1 -> "1 Besprechung"
                        else -> "$count Besprechungen"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
        }
    }
    if (showTabs) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            SegmentedButton(
                selected = tab == 0,
                onClick = { onTab(0) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {
                    Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            ) { Text("Besprechungen", maxLines = 1) }
            SegmentedButton(
                selected = tab == 1,
                onClick = { onTab(1) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {
                    Icon(Icons.Filled.TaskAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            ) {
                Text(if (openTaskCount > 0) "Aufgaben · $openTaskCount" else "Aufgaben", maxLines = 1)
            }
        }
    }
  }
}

/**
 * Einstieg in „Fragen an alle Besprechungen“: sieht aus wie ein Suchfeld, öffnet beim Antippen
 * den eigenen Bildschirm mit Suche und KI-Antwort.
 */
@Composable
private fun AskEntry(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = cs.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                "Besprechungen durchsuchen oder fragen …",
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome, contentDescription = "KI fragen",
                    tint = cs.onPrimaryContainer, modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Banner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingBanner(elapsedMs: Long, paused: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "recPulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "recAlpha",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = RecordRed.copy(alpha = 0.12f),
        contentColor = RecordRed,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.FiberManualRecord, contentDescription = null,
                modifier = Modifier.size(14.dp).scale(if (paused) 1f else 0.85f + 0.15f * alpha),
                tint = RecordRed.copy(alpha = if (paused) 0.5f else alpha),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (paused) "Aufnahme pausiert" else "Aufnahme läuft",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Tippen, um zur Aufnahme zurückzukehren",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(formatClock(elapsedMs), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelBanner(downloading: Boolean, progress: Float, status: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (downloading) "KI-Modelle werden geladen …" else "KI-Modelle fehlen",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (downloading) status.ifBlank { "Einmaliger Download läuft" }
                        else "Aufnehmen geht schon – zum Verarbeiten einmalig herunterladen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!downloading) {
                    Text("Einrichten", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (downloading) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    strokeCap = StrokeCap.Round,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Besprechungskarte

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableMeetingCard(
    meeting: Meeting,
    onClick: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDeleteRequest()
            false   // Karte springt zurück, gelöscht wird erst nach Bestätigung
        }
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val active = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            val bg by animateColorAsState(
                if (active) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                label = "swipeBg",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(bg)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (active) {
                    Icon(
                        Icons.Filled.Delete, contentDescription = "Löschen",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
    ) {
        MeetingCard(meeting = meeting, onClick = onClick, onLongClick = onDeleteRequest)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MeetingCard(meeting: Meeting, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    val preview = remember(meeting.summary.overview, meeting.segments.size, meeting.status) {
        previewText(meeting)
    }
    val speakers = remember(meeting.speakers, meeting.segments.size) { speakersOf(meeting) }
    val openTasks = meeting.tasks.count { !it.done }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                val (icon, tint) = when {
                    meeting.status == MeetingStatus.ERROR -> Icons.Filled.ErrorOutline to cs.error
                    meeting.status == MeetingStatus.RECORDING -> Icons.Filled.Mic to RecordRed
                    meeting.mode == ProtocolMode.WORT -> Icons.Filled.Forum to cs.secondary
                    meeting.status == MeetingStatus.DONE -> Icons.Filled.Description to cs.primary
                    else -> Icons.Filled.GraphicEq to cs.primary
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(tint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        meeting.title.ifBlank { "Besprechung" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetaItem(Icons.Filled.Schedule, formatTime(meeting.createdAt))
                        if (meeting.importedFrom != null) {
                            MetaItem(Icons.Filled.UploadFile, "Importiert")
                        } else if (meeting.durationSec > 0f) {
                            MetaItem(Icons.Filled.Timer, formatDurationWords(meeting.durationSec))
                        }
                    }
                }
            }

            if (preview.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            AnimatedVisibility(visible = meeting.isBusy) {
                Column(Modifier.padding(top = 12.dp)) {
                    LinearProgressIndicator(
                        progress = { meeting.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        strokeCap = StrokeCap.Round,
                        trackColor = cs.surfaceContainerHighest,
                    )
                    if (meeting.progressText.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            meeting.progressText,
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (meeting.status == MeetingStatus.ERROR && !meeting.error.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    meeting.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(meeting)
                Spacer(Modifier.width(8.dp))
                if (meeting.tasks.isNotEmpty()) {
                    MetaItem(
                        Icons.Filled.TaskAlt,
                        if (openTasks == 0) "Alle erledigt"
                        else if (openTasks == 1) "1 Aufgabe" else "$openTasks Aufgaben",
                    )
                }
                Spacer(Modifier.weight(1f))
                if (meeting.importedFrom == null) SpeakerAvatarStack(speakers = speakers, size = 24.dp)
            }
        }
    }
}

private fun previewText(m: Meeting): String = when {
    m.summary.overview.isNotBlank() -> m.summary.overview.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    m.segments.isNotEmpty() -> m.segments.take(6).joinToString(" ") { it.text.trim() }
    m.status == MeetingStatus.RECORDED -> "Noch nicht verarbeitet – öffnen, um Transkript und Protokoll zu erstellen."
    m.status == MeetingStatus.QUEUED -> "Wartet auf Verarbeitung …"
    else -> ""
}

// ---------------------------------------------------------------- Untere Leiste

@Composable
private fun BottomDock(
    recordingActive: Boolean,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            .height(104.dp),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(36.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockAction(Icons.Filled.UploadFile, "Importieren", onImport, Modifier.weight(1f))
                Spacer(Modifier.width(96.dp))
                DockAction(Icons.Filled.Tune, "Einstellungen", onSettings, Modifier.weight(1f))
            }
        }
        RecordButton(
            active = recordingActive,
            onClick = onRecord,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun DockAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecordButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "recBtn")
    val ring by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "ring",
    )
    Box(modifier = modifier.size(92.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(80.dp)
                .scale(if (active) ring else 1.08f)
                .clip(CircleShape)
                .background(
                    (if (active) RecordRed else RecordGradientStart).copy(alpha = 0.18f)
                )
        )
        Box(
            modifier = Modifier
                .size(76.dp)
                .shadow(10.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    if (active) Brush.linearGradient(listOf(RecordRed, Color(0xFFDC2626)))
                    else Brush.linearGradient(listOf(RecordGradientStart, RecordGradientEnd))
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (active) Icons.Filled.GraphicEq else Icons.Filled.Mic,
                contentDescription = if (active) "Zur laufenden Aufnahme" else "Aufnahme starten",
                tint = Color.White,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}
