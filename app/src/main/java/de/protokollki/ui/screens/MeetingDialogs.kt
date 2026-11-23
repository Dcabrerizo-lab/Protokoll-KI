package de.protokollki.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import de.protokollki.data.Meeting
import de.protokollki.data.Speaker
import de.protokollki.ui.components.SpeakerAvatar
import de.protokollki.ui.components.speakersOf

// ================================================================ Datenänderungen

/** Vollständige Sprecherliste (fehlende IDs aus den Segmenten mit Standardnamen ergänzt). */
fun completeSpeakers(m: Meeting): List<Speaker> =
    speakersOf(m).map { (id, name) -> Speaker(id, name) }

fun renameSpeaker(m: Meeting, id: Int, name: String): Meeting {
    val list = completeSpeakers(m)
    val updated = if (list.any { it.id == id }) list.map { if (it.id == id) it.copy(name = name) else it }
    else list + Speaker(id, name)
    return m.copy(speakers = updated)
}

/** Alle Beiträge von [from] gehören danach [into]; [from] verschwindet aus der Liste. */
fun mergeSpeakers(m: Meeting, from: Int, into: Int): Meeting {
    if (from == into) return m
    val speakers = completeSpeakers(m)
    return m.copy(
        segments = m.segments.map { if (it.speakerId == from) it.copy(speakerId = into) else it },
        speakers = speakers.filterNot { it.id == from },
    )
}

/** Einen einzelnen Beitrag einer anderen (ggf. neuen) Person zuordnen. */
fun reassignSegment(m: Meeting, index: Int, speakerId: Int, newName: String?): Meeting {
    if (index !in m.segments.indices) return m
    var speakers = completeSpeakers(m)
    if (speakers.none { it.id == speakerId }) {
        speakers = speakers + Speaker(speakerId, newName ?: "Person ${speakerId + 1}")
    }
    val segs = m.segments.mapIndexed { i, s -> if (i == index) s.copy(speakerId = speakerId) else s }
    // Sprecher ohne verbleibende Beiträge entfernen
    val used = segs.map { it.speakerId }.toSet()
    return m.copy(segments = segs, speakers = speakers.filter { it.id in used })
}

// ================================================================ Dialoge

@Composable
fun TitleDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Titel ändern") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Titel der Besprechung") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
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

/**
 * Sprecher umbenennen oder mit einer anderen Person zusammenführen
 * (die Sprechererkennung ist nie perfekt).
 */
@Composable
fun SpeakerDialog(
    meeting: Meeting,
    speakerId: Int,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onMerge: (intoId: Int) -> Unit,
) {
    val current = meeting.speakerName(speakerId)
    var name by remember { mutableStateOf(if (current.startsWith("Person ")) "" else current) }
    var mergeInto by remember { mutableStateOf<Int?>(null) }
    val others = remember(meeting) { speakersOf(meeting).filter { it.first != speakerId } }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { SpeakerAvatar(name = current, id = speakerId, size = 48.dp) },
        title = { Text(current) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; if (it.isNotBlank()) mergeInto = null },
                    singleLine = true,
                    label = { Text("Name") },
                    placeholder = { Text("z. B. Anna Schmidt") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (others.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.MergeType, contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Mit anderer Person zusammenführen", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Falls die KI eine Person fälschlich doppelt erkannt hat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        others.forEach { (id, n) ->
                            PersonOption(
                                id = id,
                                name = n,
                                selected = mergeInto == id,
                                onClick = { mergeInto = if (mergeInto == id) null else id; name = "" },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            val target = mergeInto
            TextButton(
                onClick = {
                    if (target != null) onMerge(target) else onRename(name.trim())
                },
                enabled = target != null || name.isNotBlank(),
            ) { Text(if (target != null) "Zusammenführen" else "Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonOption(id: Int, name: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
        border = if (selected) BorderStroke(1.5.dp, cs.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeakerAvatar(name = name, id = id, size = 30.dp)
            Spacer(Modifier.width(12.dp))
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary)
        }
    }
}

/** „Sprecher ändern“ für einen einzelnen Redebeitrag. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReassignSegmentDialog(
    meeting: Meeting,
    index: Int,
    onDismiss: () -> Unit,
    onAssign: (speakerId: Int, newName: String?) -> Unit,
) {
    val seg = meeting.segments.getOrNull(index)
    if (seg == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val people = remember(meeting) { speakersOf(meeting) }
    val newId = (people.maxOfOrNull { it.first } ?: -1) + 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sprecher ändern") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "„${seg.text.trim().take(120)}${if (seg.text.trim().length > 120) " …" else ""}“",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    people.forEach { (id, n) ->
                        PersonOption(
                            id = id,
                            name = n,
                            selected = id == seg.speakerId,
                            onClick = { onAssign(id, null) },
                        )
                    }
                    Surface(
                        onClick = { onAssign(newId, "Person ${newId + 1}") },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text("Neue Person (Person ${newId + 1})", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Auswahl „Anzahl Teilnehmender“: 0 = automatisch, sonst 2–8. */
@Composable
fun SpeakerCountSelector(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = value == 0,
            onClick = { onChange(0) },
            label = { Text("Automatisch") },
        )
        (2..8).forEach { n ->
            FilterChip(
                selected = value == n,
                onClick = { onChange(n) },
                label = { Text("$n") },
            )
        }
    }
}

@Composable
fun SpeakerCountDialog(
    initial: Int,
    retranscribe: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var count by remember { mutableIntStateOf(initial.coerceIn(0, 8).let { if (it == 1) 0 else it }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Groups, contentDescription = null) },
        title = { Text(if (retranscribe) "Neu transkribieren" else "Aufnahme verarbeiten") },
        text = {
            Column(Modifier.heightIn(max = 400.dp)) {
                Text(
                    "Wie viele Personen haben gesprochen? Eine genaue Angabe verbessert die Sprechererkennung.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                SpeakerCountSelector(value = count, onChange = { count = it })
                if (retranscribe) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Transkript, Sprecherzuordnung, Namen und Protokoll werden komplett neu erstellt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(count) }) {
                Text(if (retranscribe) "Neu transkribieren" else "Verarbeiten")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
