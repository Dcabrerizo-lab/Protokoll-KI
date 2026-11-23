package de.protokollki.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.protokollki.data.Meeting
import de.protokollki.data.MeetingRepository
import de.protokollki.engine.AskEngine
import de.protokollki.engine.MeetingSearch
import de.protokollki.engine.SearchHit
import de.protokollki.ui.components.EmptyState
import de.protokollki.ui.components.OfflineBadge
import de.protokollki.ui.components.formatDateShort
import de.protokollki.ui.components.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val EXAMPLES = listOf(
    "Was haben wir zuletzt beschlossen?",
    "Welche Aufgaben sind noch offen?",
    "Wann wurde über Budget gesprochen?",
)

/**
 * Fragen an alle Besprechungen: sofortige Fundstellen (Volltextsuche) und eine kurze KI-Antwort
 * mit antippbaren Quellen. Fundstelle antippen = Besprechung an dieser Stelle öffnen und abspielen.
 */
@Composable
fun AskScreen(
    onBack: () -> Unit,
    onOpenHit: (meetingId: String, segment: Int) -> Unit,
) {
    val context = LocalContext.current
    val state by AskEngine.state.collectAsStateWithLifecycle()
    val meetings by MeetingRepository.meetings.collectAsStateWithLifecycle()
    val byId = remember(meetings) { meetings.associateBy { it.id } }
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Sprachmodell beim Verlassen freigeben (mehrere GB Arbeitsspeicher)
    DisposableEffect(Unit) { onDispose { AskEngine.release() } }

    LaunchedEffect(Unit) {
        if (query.isEmpty()) {
            delay(150)
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    // Leere Eingabe = neue Frage: alte Antwort verwerfen
    LaunchedEffect(query.isBlank()) {
        if (query.isBlank() && AskEngine.state.value.question.isNotEmpty()) AskEngine.clear()
    }

    val trimmed = query.trim()
    val submitted = state.question.isNotEmpty() && state.question == trimmed

    // Sofort-Treffer beim Tippen (ohne KI), entprellt
    val instantHits by produceState(emptyList<SearchHit>(), trimmed, meetings, submitted) {
        if (submitted || trimmed.length < 2) {
            value = emptyList()
            return@produceState
        }
        delay(300)
        value = withContext(Dispatchers.Default) {
            runCatching { MeetingSearch.search(meetings, trimmed) }.getOrDefault(emptyList())
        }
    }

    fun submit(q: String = query) {
        val text = q.trim()
        if (text.isEmpty()) return
        query = text
        keyboard?.hide()
        focusManager.clearFocus()
        AskEngine.ask(context, text)
    }

    val hits = if (submitted) state.hits else instantHits
    val highlightTerms = remember(trimmed) { highlightTermsOf(trimmed) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AskTopBar(
                query = query,
                onQueryChange = { query = it },
                onSubmit = { submit() },
                onBack = onBack,
                focus = focus,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (trimmed.isEmpty()) {
                item(key = "intro") {
                    AskIntro(onExample = { submit(it) }, hasMeetings = meetings.isNotEmpty())
                }
                return@LazyColumn
            }

            if (!submitted) {
                item(key = "askButton") {
                    FilledTonalButton(onClick = { submit() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "KI fragen: „$trimmed“",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else if (state.hits.isNotEmpty() || state.answering) {
                item(key = "answer") {
                    AnswerCard(
                        answer = state.answer,
                        answering = state.answering,
                        progressText = state.progressText,
                        error = state.error,
                        hitCount = state.hits.size,
                        onCite = { n ->
                            state.hits.getOrNull(n - 1)?.let { onOpenHit(it.meetingId, it.segment) }
                        },
                    )
                }
            }

            if (submitted && state.hits.isEmpty() && !state.answering) {
                item(key = "none") {
                    EmptyState(
                        icon = Icons.Filled.SearchOff,
                        title = "Keine Fundstellen",
                        message = state.answer.ifBlank {
                            "Zu „$trimmed“ wurde in Ihren Besprechungen nichts gefunden."
                        } + " Versuchen Sie andere Begriffe, z. B. Namen oder Themen.",
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            } else if (hits.isNotEmpty()) {
                item(key = "hitsHeader") {
                    Text(
                        if (submitted) "Fundstellen · ${hits.size}" else "Sofort-Treffer · ${hits.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
                itemsIndexed(hits, key = { i, h -> "hit_${i}_${h.meetingId}_${h.segment}" }) { i, h ->
                    HitCard(
                        number = if (submitted) i + 1 else null,
                        hit = h,
                        meeting = byId[h.meetingId],
                        terms = highlightTerms,
                        onClick = { onOpenHit(h.meetingId, h.segment) },
                    )
                }
            } else if (!submitted && trimmed.length >= 2) {
                item(key = "typingHint") {
                    Text(
                        "Noch keine Sofort-Treffer – mit „Suchen“ auf der Tastatur fragt die KI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

// ================================================================ Kopfzeile

@Composable
private fun AskTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    focus: FocusRequester,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
        }
        TextField(
            value = query,
            onValueChange = { onQueryChange(it.replace('\n', ' ')) },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
            placeholder = { Text("Frage stellen oder Begriff suchen …", maxLines = 1) },
            leadingIcon = {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Eingabe löschen")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
    }
}

// ================================================================ Leerer Zustand

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskIntro(onExample: (String) -> Unit, hasMeetings: Boolean) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Fragen an alle Besprechungen",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            OfflineBadge()
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (hasMeetings) "Durchsucht Protokolle und Transkripte. Die KI antwortet mit Quellen – " +
                "antippen öffnet die Stelle und spielt sie ab."
            else "Sobald Sie Besprechungen aufgenommen oder importiert haben, können Sie hier alles fragen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasMeetings) {
            Spacer(Modifier.height(18.dp))
            Text(
                "Zum Beispiel",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EXAMPLES.forEach { ex ->
                    AssistChip(
                        onClick = { onExample(ex) },
                        label = { Text(ex) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.AutoAwesome, contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }
        }
    }
}

// ================================================================ KI-Antwort

@Composable
private fun AnswerCard(
    answer: String,
    answering: Boolean,
    progressText: String,
    error: String?,
    hitCount: Int,
    onCite: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cs.primaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("KI-Antwort", style = MaterialTheme.typography.titleSmall, color = cs.primary)
            }
            Spacer(Modifier.height(10.dp))
            if (answer.isNotBlank()) {
                CitedText(answer = answer, maxCitation = hitCount, onCite = onCite)
            } else if (answering) {
                ShimmerLines()
            }
            AnimatedVisibility(visible = answering, enter = fadeIn(), exit = fadeOut()) {
                Column(Modifier.padding(top = 10.dp)) {
                    if (answer.isNotBlank()) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        progressText.ifBlank { "Antwort wird formuliert …" },
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            if (!error.isNullOrBlank()) {
                if (answer.isNotBlank() || answering) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Filled.Info, contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        error + if (hitCount > 0) " Die Fundstellen finden Sie unten." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val CITATION = Regex("\\[(\\d{1,2})]")

/** Antworttext, in dem Quellenmarker „[n]“ als kleine antippbare Chips erscheinen. */
@Composable
private fun CitedText(answer: String, maxCitation: Int, onCite: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val numbers = mutableSetOf<Int>()
    val text = buildAnnotatedString {
        var last = 0
        CITATION.findAll(answer).forEach { m ->
            val n = m.groupValues[1].toInt()
            append(answer.substring(last, m.range.first))
            if (n in 1..maxCitation) {
                numbers += n
                appendInlineContent("cite_$n", "[$n]")
            } else {
                append(m.value)
            }
            last = m.range.last + 1
        }
        append(answer.substring(last))
    }
    val inline = numbers.associate { n ->
        val width = if (n >= 10) 26.sp else 20.sp
        "cite_$n" to InlineTextContent(
            Placeholder(width = width, height = 16.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(50))
                    .background(cs.primary)
                    .clickable { onCite(n) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$n",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = cs.onPrimary,
                    maxLines = 1,
                )
            }
        }
    }
    Text(
        text,
        inlineContent = inline,
        style = MaterialTheme.typography.bodyLarge,
        color = cs.onSurface,
    )
}

@Composable
private fun ShimmerLines() {
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(
        initialValue = 0.25f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "shimmerAlpha",
    )
    val color = MaterialTheme.colorScheme.primary.copy(alpha = a * 0.35f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1f, 0.92f, 0.6f).forEach { f ->
            Box(
                Modifier
                    .fillMaxWidth(f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color)
            )
        }
    }
}

// ================================================================ Fundstellen

@Composable
private fun HitCard(
    number: Int?,
    hit: SearchHit,
    meeting: Meeting?,
    terms: List<String>,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val imported = meeting?.importedFrom != null
    val canPlay = meeting != null && !meeting.audioDeleted
    val excerpt = remember(hit.text, terms) { excerptAround(hit.text, terms) }
    val highlighted = remember(excerpt, terms, cs.primary) { highlight(excerpt, terms, cs.primary) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp)) {
            if (number != null) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$number", style = MaterialTheme.typography.labelSmall, color = cs.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        hit.title.ifBlank { "Besprechung" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatDateShort(hit.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                val source = when {
                    hit.segment < 0 -> "aus dem Protokoll"
                    imported -> "aus dem Originaltext"
                    else -> hit.speaker
                }
                if (source.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        source,
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    highlighted,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface.copy(alpha = 0.9f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hit.segment >= 0 && !imported) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(cs.primary.copy(alpha = 0.08f))
                            .padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (canPlay) Icons.Filled.PlayArrow else Icons.Filled.FormatQuote,
                            contentDescription = if (canPlay) "Ab hier abspielen" else null,
                            tint = cs.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            formatDuration(hit.start),
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.primary,
                        )
                    }
                }
            }
        }
    }
}

// ================================================================ Hilfsfunktionen

private val WORD = Regex("[\\p{L}\\p{N}]+")
private val IGNORE = setOf(
    "der", "die", "das", "und", "oder", "was", "wer", "wie", "wann", "wurde", "wurden", "haben", "hat",
    "wir", "ihr", "sie", "über", "ueber", "zum", "zur", "den", "dem", "des", "ein", "eine", "ist",
    "sind", "noch", "welche", "welcher", "welches", "zuletzt", "beschlossen", "gesprochen", "für",
)

/** Suchbegriffe zum Hervorheben (ohne Füllwörter), als kurze Wortstämme. */
private fun highlightTermsOf(query: String): List<String> =
    WORD.findAll(query.lowercase()).map { it.value }
        .filter { it.length >= 3 && it !in IGNORE }
        .map { if (it.length > 5) it.dropLast(2) else it }
        .distinct()
        .toList()

/** Langen Text um die erste Fundstelle kürzen, damit der Treffer sichtbar ist. */
private fun excerptAround(text: String, terms: List<String>): String {
    val t = text.trim().replace(Regex("\\s+"), " ")
    if (t.length <= 220 || terms.isEmpty()) return t
    val lower = t.lowercase()
    val first = terms.mapNotNull { term -> lower.indexOf(term).takeIf { it >= 0 } }.minOrNull() ?: return t
    if (first < 80) return t
    var start = (first - 60).coerceAtLeast(0)
    val space = t.lastIndexOf(' ', start)
    if (space > 0 && start - space < 20) start = space + 1
    return "… " + t.substring(start)
}

/** Wörter, die einen Suchbegriff enthalten, fett und farbig hinterlegt. */
private fun highlight(text: String, terms: List<String>, color: Color): AnnotatedString {
    if (terms.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        val style = SpanStyle(fontWeight = FontWeight.SemiBold, background = color.copy(alpha = 0.14f))
        WORD.findAll(text).forEach { w ->
            val lw = w.value.lowercase()
            if (terms.any { lw.contains(it) }) addStyle(style, w.range.first, w.range.last + 1)
        }
    }
}
