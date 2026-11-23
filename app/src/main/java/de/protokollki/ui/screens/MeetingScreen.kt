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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import de.protokollki.engine.VoiceProfiles
import de.protokollki.export.ExportFormat
import de.protokollki.export.Exporter
import de.protokollki.service.ProcessingService
import de.protokollki.tts.ReadAloud
import de.protokollki.ui.components.NO_GERMAN_VOICE_MESSAGE
import de.protokollki.ui.components.QuoteCard
import de.protokollki.ui.components.ReadAloudBar
import de.protokollki.ui.components.ReadAloudErrorDialog
import de.protokollki.ui.components.UiScope
import de.protokollki.ui.components.startReadAloud
import de.protokollki.ui.components.MetaItem
import de.protokollki.ui.components.PlayerBar
import de.protokollki.ui.components.SpeakerAvatar
import de.protokollki.ui.components.formatDate
import de.protokollki.ui.components.formatDurationWords
import de.protokollki.ui.components.formatTime
import de.protokollki.ui.components.rememberPlayer
import de.protokollki.ui.components.speakersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAB_SUMMARY = "summary"
private const val TAB_TRANSCRIPT = "transcript"
private const val TAB_TASKS = "tasks"

@Composable
fun MeetingScreen(
    meetingId: String,
    onBack: () -> Unit,
    onOpenRecording: () -> Unit,
    onOpenSettings: () -> Unit = {},
    initialTab: String? = null,
    onOpenMeeting: (String) -> Unit = {},
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
        initialTab = initialTab,
        onOpenMeeting = onOpenMeeting,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MeetingContent(
    m: Meeting,
    onBack: () -> Unit,
    onOpenRecording: () -> Unit,
    onOpenSettings: () -> Unit,
    initialTab: String?,
    onOpenMeeting: (String) -> Unit,
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
    var anonymized by rememberSaveable { mutableStateOf(false) }
    var showAgenda by remember { mutableStateOf(false) }
    var showSensitive by remember { mutableStateOf(false) }
    var quoteRef by remember { mutableStateOf<Int?>(null) }
    var lastQuote by remember { mutableIntStateOf(-1) }
    var highlightRef by remember { mutableIntStateOf(-1) }
    var ttsError by remember { mutableStateOf<String?>(null) }
    var ttsAttempt by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val ra by ReadAloud.state.collectAsStateWithLifecycle()

    val busy = m.isBusy
    val recordingThis = rec.active && rec.meetingId == m.id
    val canRegenerate = m.segments.isNotEmpty() && !busy && ms.installed &&
        m.status != MeetingStatus.RECORDING
    val canReadAloud = !m.summary.isEmpty || m.tasks.isNotEmpty()
    val canEditAgenda = !busy && (m.status == MeetingStatus.RECORDED || m.status == MeetingStatus.DONE ||
        m.status == MeetingStatus.ERROR)

    // Fehler beim Vorlesen (z. B. keine deutsche Stimme) anzeigen
    val raError = ra.error?.takeIf { it.isNotBlank() }
    LaunchedEffect(raError, ttsAttempt) { if (raError != null && ttsAttempt > 0) ttsError = raError }
    // Zitat-Karte blendet sich nach einigen Sekunden aus
    LaunchedEffect(quoteRef) {
        if (quoteRef != null) {
            delay(9000)
            quoteRef = null
        }
    }
    LaunchedEffect(highlightRef) {
        if (highlightRef >= 0) {
            delay(2500)
            highlightRef = -1
        }
    }

    // ---------------------------------------------------------------- Player
    val player = rememberPlayer(m.audioFile, enabled = m.status != MeetingStatus.RECORDING && !m.audioDeleted)
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

    /** Quellenbeleg: Stelle abspielen (ohne Tabwechsel) und das Zitat kurz einblenden. */
    fun cite(ref: Int) {
        val seg = m.segments.getOrNull(ref) ?: return
        if (player != null && player.ready) {
            if (ra.active && !ra.paused) ReadAloud.pause()
            player.playFrom(seg.start)
        }
        lastQuote = ref
        quoteRef = ref
    }

    fun toggleReadAloud() {
        if (ra.active) {
            ReadAloud.stop()
        } else {
            player?.pause()
            ttsAttempt++
            try {
                startReadAloud(context, m)
            } catch (e: Exception) {
                ttsError = e.message ?: NO_GERMAN_VOICE_MESSAGE
            }
        }
    }

    fun shareNow(f: ExportFormat, anon: Boolean) {
        share(context, m, f, anon)
    }

    // ---------------------------------------------------------------- Tabs
    val tabs = if (m.mode == ProtocolMode.WORT) listOf(TAB_TRANSCRIPT, TAB_TASKS)
    else listOf(TAB_SUMMARY, TAB_TRANSCRIPT, TAB_TASKS)
    var selectedTabKey by rememberSaveable(m.id) {
        mutableStateOf(if (initialTab != null && initialTab in tabs) initialTab else tabs.first())
    }
    var lastMode by rememberSaveable(m.id) { mutableStateOf(m.mode) }
    LaunchedEffect(m.mode) {
        if (m.mode != lastMode) {
            lastMode = m.mode
            selectedTabKey = tabs.first()
        }
    }

    fun showInTranscript(ref: Int) {
        quoteRef = null
        highlightRef = ref
        selectedTabKey = TAB_TRANSCRIPT
        scope.launch {
            delay(60)
            // Elemente vor dem Transkript: Kopf, Modus, Status, Reiter, Abstand, Hinweis (= 6).
            // Ein Element früher ansteuern, damit der Beitrag nicht unter den Reitern liegt.
            runCatching { listState.animateScrollToItem((6 + ref - 1).coerceAtLeast(0)) }
        }
    }
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
                    if (canReadAloud) {
                        IconButton(onClick = { toggleReadAloud() }) {
                            Icon(
                                if (ra.active) Icons.Filled.StopCircle else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (ra.active) "Vorlesen beenden" else "Vorlesen",
                                tint = if (ra.active) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                    }
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
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Anonymisiert")
                                            Text(
                                                "Klientendaten schwärzen",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
                                    trailingIcon = {
                                        Switch(
                                            checked = anonymized,
                                            onCheckedChange = { anonymized = it },
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    },
                                    onClick = { anonymized = !anonymized },
                                )
                                HorizontalDivider()
                                ExportFormat.entries.forEach { f ->
                                    DropdownMenuItem(
                                        text = { Text(if (anonymized) "${f.label} (anonymisiert)" else f.label) },
                                        leadingIcon = { Icon(exportIcon(f), contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            shareNow(f, anonymized)
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
                                if (canEditAgenda) {
                                    DropdownMenuItem(
                                        text = { Text("Tagesordnung bearbeiten") },
                                        leadingIcon = { Icon(Icons.Filled.FormatListNumbered, contentDescription = null) },
                                        onClick = { menuOpen = false; showAgenda = true },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Sensible Daten") },
                                    leadingIcon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
                                    enabled = m.status != MeetingStatus.RECORDING,
                                    onClick = { menuOpen = false; showSensitive = true },
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
            Column {
                ReadAloudBar(navigationPadding = player == null)
                if (player != null) PlayerBar(player)
            }
        },
    ) { padding ->
      Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
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
                        onCite = { cite(it) },
                        onEditAgenda = if (canEditAgenda) ({ showAgenda = true }) else null,
                    )
                    TAB_TRANSCRIPT -> transcriptItems(
                        m = m,
                        activeIndex = if (highlightRef >= 0) highlightRef else activeIndex,
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
                        onCite = { cite(it) },
                        onOpenMeeting = onOpenMeeting,
                    )
                }
            }
        }

        // Zitat zum Quellenbeleg
        AnimatedVisibility(
            visible = quoteRef != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = padding.calculateBottomPadding() + 12.dp, start = 12.dp, end = 12.dp),
        ) {
            val shown = lastQuote
            if (shown >= 0) {
                QuoteCard(
                    m = m,
                    ref = shown,
                    onShowInTranscript = { showInTranscript(shown) },
                    onClose = { quoteRef = null },
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
        val canRemember = remember(m.id, sid) {
            runCatching { VoiceProfiles.canEnrollFromMeeting(m.id, sid) }.getOrDefault(false)
        }
        SpeakerDialog(
            meeting = m,
            speakerId = sid,
            canRememberVoice = canRemember,
            onDismiss = { speakerToEdit = null },
            onRename = { name, rememberVoice ->
                speakerToEdit = null
                MeetingRepository.update(m.id) { renameSpeaker(it, sid, name) }
                if (rememberVoice) {
                    val app = context.applicationContext
                    val meetingId = m.id
                    UiScope.launch {
                        val result = try {
                            withContext(Dispatchers.Default) {
                                VoiceProfiles.enrollFromMeeting(app, meetingId, sid, name)
                            }
                            null
                        } catch (e: Exception) {
                            e.message ?: "Unbekannter Fehler"
                        }
                        scope.launch {
                            if (result == null) offerRegenerate("$name wird künftig automatisch erkannt.")
                            else snackbar.showSnackbar("Stimme konnte nicht gespeichert werden: $result")
                        }
                    }
                } else {
                    offerRegenerate("Sprecher umbenannt.")
                }
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

    if (showAgenda) {
        AgendaSheet(
            meetingId = m.id,
            onDismiss = { changed ->
                showAgenda = false
                val cur = MeetingRepository.get(m.id)
                if (changed && cur != null && cur.status == MeetingStatus.DONE && cur.segments.isNotEmpty() &&
                    ms.installed && !cur.isBusy
                ) {
                    scope.launch {
                        val r = snackbar.showSnackbar(
                            message = "Tagesordnung gespeichert. Protokoll danach neu gliedern?",
                            actionLabel = "Protokoll neu erstellen",
                            withDismissAction = true,
                            duration = SnackbarDuration.Long,
                        )
                        if (r == SnackbarResult.ActionPerformed) regenerate()
                    }
                }
            },
        )
    }

    if (showSensitive) {
        SensitiveTermsSheet(
            meetingId = m.id,
            onDismiss = { showSensitive = false },
            onShareAnonymized = {
                showSensitive = false
                shareNow(ExportFormat.PDF, true)
            },
        )
    }

    ttsError?.let { msg ->
        ReadAloudErrorDialog(message = msg, onDismiss = { ttsError = null }, context = context)
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
    val recognized = remember(m.speakers) { m.speakers.filter { it.profileId != null }.map { it.id }.toSet() }
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
        if (m.audioDeleted) {
            Spacer(Modifier.height(6.dp))
            MetaItem(Icons.Filled.DeleteSweep, "Audio per Löschfrist entfernt – Transkript bleibt erhalten")
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
                            if (id in recognized) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Filled.Verified,
                                    contentDescription = "An der Stimme erkannt",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
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

private fun share(context: Context, m: Meeting, f: ExportFormat, anonymized: Boolean) {
    try {
        Exporter.share(context, m, f, anonymized)
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
