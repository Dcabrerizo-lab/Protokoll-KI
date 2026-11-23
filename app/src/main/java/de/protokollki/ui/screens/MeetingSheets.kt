package de.protokollki.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.protokollki.data.AppSettings
import de.protokollki.data.Meeting
import de.protokollki.data.MeetingRepository
import de.protokollki.privacy.Redactor
import de.protokollki.ui.components.speakersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ================================================================ Begriffe bearbeiten (Chips)

/**
 * Bearbeitbare Begriffsliste als Chips (Tippen auf ✕ entfernt) mit Eingabefeld.
 * Mehrere Begriffe lassen sich mit Komma oder Zeilenumbruch auf einmal einfügen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TermChipsEditor(
    terms: List<String>,
    onChange: (List<String>) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    fun add(raw: String) {
        val parts = raw.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val existing = terms.map { it.lowercase() }.toMutableSet()
        val added = parts.filter { existing.add(it.lowercase()) }
        if (added.isNotEmpty()) onChange(terms + added)
    }
    Column(modifier) {
        if (terms.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                terms.forEach { t ->
                    InputChip(
                        selected = false,
                        onClick = { onChange(terms - t) },
                        label = { Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "„$t“ entfernen",
                                modifier = Modifier.size(InputChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        OutlinedTextField(
            value = input,
            onValueChange = { v ->
                if (v.contains('\n')) {
                    add(v); input = ""
                } else {
                    input = v
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(placeholder) },
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { add(input); input = "" }),
            trailingIcon = {
                IconButton(onClick = { add(input); input = "" }, enabled = input.isNotBlank()) {
                    Icon(Icons.Filled.Add, contentDescription = "Hinzufügen")
                }
            },
        )
    }
}

// ================================================================ Tagesordnung

/**
 * Tagesordnung bearbeiten. Jede Änderung wird sofort gespeichert.
 * [onDismiss] meldet, ob sich die Tagesordnung geändert hat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaSheet(meetingId: String, onDismiss: (changed: Boolean) -> Unit) {
    val initial = remember { MeetingRepository.get(meetingId)?.agenda.orEmpty() }
    var items by remember { mutableStateOf(initial) }
    var input by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = remember { FocusRequester() }

    fun save(list: List<String>) {
        items = list
        MeetingRepository.update(meetingId) { it.copy(agenda = list) }
    }
    fun addFrom(raw: String) {
        val parts = raw.split('\n').map { it.trim().trimStart('-', '•', '*').trim() }.filter { it.isNotEmpty() }
        if (parts.isNotEmpty()) save(items + parts)
    }
    val finish: () -> Unit = {
        if (input.isNotBlank()) addFrom(input)
        input = ""
        onDismiss(items != initial)
    }

    LaunchedEffect(Unit) {
        if (initial.isEmpty()) {
            delay(350)
            runCatching { focus.requestFocus() }
        }
    }

    ModalBottomSheet(onDismissRequest = finish, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FormatListNumbered, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Tagesordnung", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Die KI gliedert das Protokoll nach diesen Punkten.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEachIndexed { i, top ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${i + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(top, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { save(items.filterIndexed { j, _ -> j != i }) }) {
                                Icon(Icons.Filled.Close, contentDescription = "TOP ${i + 1} entfernen")
                            }
                        }
                    }
                }
            }
            if (items.isNotEmpty()) Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { v ->
                    if (v.contains('\n')) {
                        // Eingefügte Liste: jede Zeile wird ein TOP
                        val lines = v.split('\n')
                        addFrom(lines.dropLast(1).joinToString("\n"))
                        input = lines.last()
                    } else {
                        input = v
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                singleLine = true,
                placeholder = { Text("TOP ${items.size + 1} eingeben …") },
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = {
                    addFrom(input); input = ""
                }),
                trailingIcon = {
                    IconButton(onClick = { addFrom(input); input = "" }, enabled = input.isNotBlank()) {
                        Icon(Icons.Filled.Add, contentDescription = "Hinzufügen")
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = finish, modifier = Modifier.fillMaxWidth()) { Text("Fertig") }
        }
    }
}

// ================================================================ Sensible Daten

/**
 * Begriffe dieser Besprechung, die beim anonymisierten Export geschwärzt werden, mit Live-Vorschau.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SensitiveTermsSheet(
    meetingId: String,
    onDismiss: () -> Unit,
    onShareAnonymized: () -> Unit,
) {
    val meetings by MeetingRepository.meetings.collectAsStateWithLifecycle()
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    val found: Meeting? = meetings.firstOrNull { it.id == meetingId }
    if (found == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val m: Meeting = found
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val custom = settings.customTerms
    val preview by produceState(emptyList<Pair<String, String>>(), m, custom) {
        value = withContext(Dispatchers.Default) {
            runCatching { Redactor.preview(m, custom) }.getOrDefault(emptyList())
        }
    }
    val suggestions = remember(m.speakers, m.segments.size, m.sensitiveTerms) {
        val have = m.sensitiveTerms.map { it.lowercase() }.toSet()
        speakersOf(m).map { it.second }
            .filter { !it.startsWith("Person ") && it.lowercase() !in have }
    }

    fun setTerms(list: List<String>) {
        MeetingRepository.update(meetingId) { it.copy(sensitiveTerms = list) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Sensible Daten", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Diese Namen und Begriffe werden beim anonymisierten Export ersetzt. " +
                    "Telefonnummern, E-Mail-Adressen, IBAN, Geburtsdaten und Adressen werden automatisch geschwärzt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            TermChipsEditor(
                terms = m.sensitiveTerms,
                onChange = { setTerms(it) },
                placeholder = "Name oder Begriff hinzufügen",
            )
            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Teilnehmende ebenfalls schwärzen?",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s ->
                        AssistChip(
                            onClick = { setTerms(m.sensitiveTerms + s) },
                            label = { Text(s) },
                            leadingIcon = {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                        )
                    }
                }
            }
            if (custom.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Zusätzlich aus den Einstellungen („Immer schwärzen“): " + custom.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(18.dp))
            Text("Vorschau", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            if (preview.isEmpty()) {
                Text(
                    "Im Moment würde nichts ersetzt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        preview.take(40).forEach { (orig, repl) ->
                            Row(
                                modifier = Modifier.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    orig,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "wird zu",
                                    modifier = Modifier.padding(horizontal = 8.dp).size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    repl,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (preview.size > 40) {
                            Text(
                                "… und ${preview.size - 40} weitere",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            FilledTonalButton(onClick = onShareAnonymized, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Anonymisiert als PDF teilen")
            }
        }
    }
}
