package de.protokollki.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.protokollki.audio.Recorder
import de.protokollki.data.Meeting
import de.protokollki.data.MeetingRepository
import de.protokollki.data.MeetingStatus
import de.protokollki.data.ProtocolMode
import de.protokollki.data.Segment
import de.protokollki.engine.ModelManager
import de.protokollki.export.ExportFormat
import de.protokollki.export.Exporter
import de.protokollki.service.ProcessingService
import de.protokollki.ui.components.MetaItem
import de.protokollki.ui.components.PlayerBar
import de.protokollki.ui.components.SpeakerAvatar
import de.protokollki.ui.components.formatDate
import de.protokollki.ui.components.formatDurationWords
import de.protokollki.ui.components.formatTime
import de.protokollki.ui.components.rememberPlayer
import de.protokollki.ui.components.speakersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAB_SUMMARY = "summary"
private const val TAB_TRANSCRIPT = "transcript"
private const val TAB_TASKS = "tasks"

@Composable
fun MeetingScreen(
    meetingId: String,
    onBack: () -> Unit,
    onOpenRecording: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val meetings by MeetingRepository.meetings.collectAsStateWithLifecycle()
    val meeting = meetings.firstOrNull { it.id == meetingId }
    var left by remember { mutableStateOf(false) }
    var seen by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!left) {
            left = true
            onBack()
        }
    }

    // Besprechung fehlt (gelöscht oder ungültige ID) -> zurück
    LaunchedEffect(meeting == null) {
        if (meeting == null) {
            delay(if (seen) 0L else 1500L)
            if (MeetingRepository.get(meetingId) == null) leave()
        } else {
            seen = true
        }
    }

    if (meeting == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    MeetingContent(
        m = meeting,
        onBack = leave,
        onOpenRecording = onOpenRecording,
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MeetingContent(
    m: Meeting,
    onBack: () -> Unit,
    onOpenRecording: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val ms by remember { ModelManager.state }.collectAsStateWithLifecycle()
    val rec by Recorder.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showTitleDialog by remember { mutableStateOf(false) }
    var speakerToEdit by remember { mutableStateOf<Int?>(null) }
    var segmentToReassign by remember { mutableStateOf<Int?>(null) }
    var showCountDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }

    val busy = m.isBusy
    val recordingThis = rec.active && rec.meetingId == m.id
    val canRegenerate = m.segments.isNotEmpty() && !busy && ms.installed &&
        m.status != MeetingStatus.RECORDING

    // ---------------------------------------------------------------- Player
    val player = rememberPlayer(m.audioFile, enabled = m.status != MeetingStatus.RECORDING)
    val segments by rememberUpdatedState(m.segments)
    val activeIndex by remember(player) {
        derivedStateOf {
            val p = player ?: return@derivedStateOf -1
            if (!p.playing && p.positionMs == 0) -1 else findSegment(segments, p.positionMs)
        }
    }

    // ---------------------------------------------------------------- Aktionen
    fun regenerate() {
        ProcessingService.regenerate(context, m.id)
    }

    fun offerRegenerate(message: String) {
        val hasProtocol = !m.summary.isEmpty || m.tasks.isNotEmpty()
        if (!hasProtocol || !ms.installed || m.isBusy) {
            scope.launch { snackbar.showSnackbar(message) }
            return
        }
        scope.launch {
            val r = snackbar.showSnackbar(
                message = "$message Protokoll mit den neuen Namen aktualisieren?",
                actionLabel = "Neu erstellen",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (r == SnackbarResult.ActionPerformed) regenerate()
        }
    }

    fun process(count: Int) {
        MeetingRepository.update(m.id) { it.copy(expectedSpeakers = count, error = null) }
        ProcessingService.process(context, m.id)
    }

    fun changeMode(mode: ProtocolMode) {
        if (mode == m.mode) return
        MeetingRepository.update(m.id) { it.copy(mode = mode) }
        if (mode == ProtocolMode.ERGEBNIS && m.summary.isEmpty && canRegenerate) regenerate()
    }

    // ---------------------------------------------------------------- Tabs
    val tabs = if (m.mode == ProtocolMode.WORT) listOf(TAB_TRANSCRIPT, TAB_TASKS)
    else listOf(TAB_SUMMARY, TAB_TRANSCRIPT, TAB_TASKS)
    var selectedTabKey by rememberSaveable(m.id) { mutableStateOf(tabs.first()) }
    LaunchedEffect(m.mode) { selectedTabKey = tabs.first() }
    val selectedTab = if (selectedTabKey in tabs) selectedTabKey else tabs.first()
    val openTasks = m.tasks.count { !it.done }
    val hasContent = m.segments.isNotEmpty() || m.status == MeetingStatus.DONE

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            MediumTopAppBar(
                title = {
                    Row(
                        modifier = Modifier.clickable { showTitleDialog = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            m.title.ifBlank { "Besprechung" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.Edit, contentDescription = "Titel ändern",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { exportMenu = true; menuOpen = true },
                        enabled = m.status != MeetingStatus.RECORDING,
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Teilen")
                    }
                    Box {
                        IconButton(onClick = { exportMenu = false; menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Mehr")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            if (exportMenu) {
                                Text(
                                    "Exportieren & teilen",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                ExportFormat.entries.forEach { f ->
                                    DropdownMenuItem(
                                        text = { Text(f.label) },
                                        leadingIcon = { Icon(exportIcon(f), contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            share(context, m, f)
                                        },
                                    )
                                }
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Exportieren / Teilen") },
                                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                    trailingIcon = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                                    enabled = m.status != MeetingStatus.RECORDING,
                                    onClick = { exportMenu = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Text kopieren") },
                                    leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        copyText(context, m)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Titel ändern") },
                                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                    onClick = { menuOpen = false; showTitleDialog = true },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Protokoll neu erstellen") },
                                    leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                                    enabled = canRegenerate,
                                    onClick = { menuOpen = false; regenerate() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Neu transkribieren") },
                                    leadingIcon = { Icon(Icons.Filled.GraphicEq, contentDescription = null) },
                                    enabled = !busy && ms.installed && m.status != MeetingStatus.RECORDING,
                                    onClick = { menuOpen = false; showCountDialog = true },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Löschen", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.Delete, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = { menuOpen = false; confirmDelete = true },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (player != null) PlayerBar(player)
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                MeetingHeader(m = m, onSpeakerClick = { speakerToEdit = it })
            }
            item(key = "mode") {
                ModeSelector(
                    mode = m.mode,
                    enabled = m.status != MeetingStatus.RECORDING,
                    onChange = { changeMode(it) },
                )
            }
            item(key = "status") {
                AnimatedVisibility(
                    visible = m.status != MeetingStatus.DONE,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    StatusSection(
                        m = m,
                        modelsInstalled = ms.installed,
                        recordingThis = recordingThis,
                        onOpenRecording = onOpenRecording,
                        onProcess = { process(it) },
                        onCancel = { ProcessingService.cancel(context) },
                        onRetry = {
                            if (m.segments.isEmpty()) process(m.expectedSpeakers) else regenerate()
                        },
                        onOpenModels = onOpenSettings,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (hasContent) {
                stickyHeader(key = "tabs") {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        PrimaryTabRow(
                            selectedTabIndex = tabs.indexOf(selectedTab).coerceAtLeast(0),
                            containerColor = MaterialTheme.colorScheme.background,
                        ) {
                            tabs.forEach { key ->
                                Tab(
                                    selected = key == selectedTab,
                                    onClick = { selectedTabKey = key },
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                when (key) {
                                                    TAB_SUMMARY -> "Zusammenfassung"
                                                    TAB_TRANSCRIPT ->
                                                        if (m.mode == ProtocolMode.WORT) "Wortprotokoll" else "Transkript"
                                                    else -> "Aufgaben"
                                                },
                                                maxLines = 1,
                                            )
                                            if (key == TAB_TASKS && openTasks > 0) {
                                                Spacer(Modifier.width(6.dp))
                                                Badge { Text("$openTasks") }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                item(key = "tabSpacer") { Spacer(Modifier.height(4.dp)) }

                when (selectedTab) {
                    TAB_SUMMARY -> summaryItems(
                        m = m,
                        canGenerate = canRegenerate,
                        onGenerate = { regenerate() },
                    )
                    TAB_TRANSCRIPT -> transcriptItems(
                        m = m,
                        activeIndex = activeIndex,
                        verbatim = m.mode == ProtocolMode.WORT,
                        onSeek = { seg -> player?.playFrom(seg.start) },
                        onLongPress = { idx -> if (!busy) segmentToReassign = idx },
                    )
                    else -> taskItems(
                        m = m,
                        onToggle = { idx ->
                            MeetingRepository.update(m.id) { cur ->
                                cur.copy(tasks = cur.tasks.mapIndexed { i, t ->
                                    if (i == idx) t.copy(done = !t.done) else t
                                })
                            }
                        },
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- Dialoge

    if (showTitleDialog) {
        TitleDialog(
            current = m.title,
            onDismiss = { showTitleDialog = false },
            onSave = { t ->
                showTitleDialog = false
                if (t.isNotBlank()) MeetingRepository.update(m.id) { it.copy(title = t) }
            },
        )
    }

    speakerToEdit?.let { sid ->
        SpeakerDialog(
            meeting = m,
            speakerId = sid,
            onDismiss = { speakerToEdit = null },
            onRename = { name ->
                speakerToEdit = null
                MeetingRepository.update(m.id) { renameSpeaker(it, sid, name) }
                offerRegenerate("Sprecher umbenannt.")
            },
            onMerge = { into ->
                speakerToEdit = null
                MeetingRepository.update(m.id) { mergeSpeakers(it, sid, into) }
                offerRegenerate("Sprecher zusammengeführt.")
            },
        )
    }

    segmentToReassign?.let { idx ->
        ReassignSegmentDialog(
            meeting = m,
            index = idx,
            onDismiss = { segmentToReassign = null },
            onAssign = { sid, newName ->
                segmentToReassign = null
                MeetingRepository.update(m.id) { reassignSegment(it, idx, sid, newName) }
                offerRegenerate("Sprecher geändert.")
            },
        )
    }

    if (showCountDialog) {
        SpeakerCountDialog(
            initial = m.expectedSpeakers,
            retranscribe = m.segments.isNotEmpty() || m.status == MeetingStatus.DONE,
            onDismiss = { showCountDialog = false },
            onConfirm = { n ->
                showCountDialog = false
                player?.pause()
                process(n)
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Besprechung löschen?") },
            text = {
                Text(
                    if (recordingThis) "Diese Besprechung wird gerade aufgenommen. Beenden Sie zuerst die Aufnahme."
                    else "Aufnahme, Transkript und Protokoll werden endgültig von diesem Gerät gelöscht."
                )
            },
            confirmButton = {
                if (!recordingThis) {
                    TextButton(
                        onClick = {
                            confirmDelete = false
                            player?.pause()
                            if (busy) ProcessingService.cancel(context)
                            MeetingRepository.delete(m.id)
                            onBack()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Löschen") }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(if (recordingThis) "OK" else "Abbrechen")
                }
            },
        )
    }
}

// ================================================================ Kopfbereich

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeetingHeader(m: Meeting, onSpeakerClick: (Int) -> Unit) {
    val speakers = remember(m.speakers, m.segments) { speakersOf(m) }
    val shares = remember(m.segments) {
        val total = m.segments.sumOf { (it.end - it.start).coerceAtLeast(0f).toDouble() }
        if (total <= 0.0) emptyMap()
        else m.segments.groupBy { it.speakerId }.mapValues { (_, list) ->
            (list.sumOf { (it.end - it.start).coerceAtLeast(0f).toDouble() } * 100 / total).toInt()
        }
    }
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MetaItem(Icons.Filled.CalendarToday, formatDate(m.createdAt))
            MetaItem(Icons.Filled.Schedule, formatTime(m.createdAt))
            if (m.durationSec > 0f) MetaItem(Icons.Filled.Timer, formatDurationWords(m.durationSec))
        }
        if (speakers.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Teilnehmende · zum Umbenennen antippen",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                speakers.forEach { (id, name) ->
                    Surface(
                        onClick = { onSpeakerClick(id) },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SpeakerAvatar(name = name, id = id, size = 28.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            val share = shares[id]
                            if (share != null) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "$share %",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(mode: ProtocolMode, enabled: Boolean, onChange: (ProtocolMode) -> Unit) {
    val modes = listOf(ProtocolMode.ERGEBNIS, ProtocolMode.WORT)
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        modes.forEachIndexed { i, pm ->
            SegmentedButton(
                selected = mode == pm,
                onClick = { onChange(pm) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                enabled = enabled,
            ) {
                Text(pm.label, maxLines = 1)
            }
        }
    }
}

// ================================================================ Hilfsfunktionen

private fun findSegment(segments: List<Segment>, posMs: Int): Int {
    val sec = posMs / 1000f
    var idx = -1
    for (i in segments.indices) {
        if (segments[i].start <= sec) idx = i else break
    }
    if (idx >= 0 && sec > segments[idx].end + 1.5f) return -1
    return idx
}

private fun exportIcon(f: ExportFormat): ImageVector = when (f) {
    ExportFormat.PDF -> Icons.Filled.PictureAsPdf
    ExportFormat.DOCX -> Icons.Filled.Description
    ExportFormat.MARKDOWN -> Icons.Filled.Code
    else -> Icons.Filled.TextFields
}

private fun share(context: Context, m: Meeting, f: ExportFormat) {
    try {
        Exporter.share(context, m, f)
    } catch (e: Exception) {
        Toast.makeText(context, "Export fehlgeschlagen: ${e.message ?: "unbekannter Fehler"}", Toast.LENGTH_LONG).show()
    }
}

private fun copyText(context: Context, m: Meeting) {
    try {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText(m.title, Exporter.plainText(m)))
        Toast.makeText(context, "In die Zwischenablage kopiert", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Kopieren fehlgeschlagen", Toast.LENGTH_SHORT).show()
    }
}
