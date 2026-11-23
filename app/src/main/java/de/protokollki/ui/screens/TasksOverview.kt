package de.protokollki.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.protokollki.data.Meeting
import de.protokollki.data.TaskItem
import de.protokollki.ui.components.DueState
import de.protokollki.ui.components.EmptyState
import de.protokollki.ui.components.SpeakerAvatar
import de.protokollki.ui.components.dueState
import de.protokollki.ui.components.formatDateShort
import de.protokollki.ui.components.isOpenValue
import de.protokollki.ui.components.parseDueDate
import de.protokollki.ui.theme.successColor

private const val NO_OWNER = "Ohne Zuständigkeit"

/** Eine Personengruppe in der Aufgabenübersicht. */
private class OwnerGroup(val name: String, val tasks: List<Pair<Meeting, TaskItem>>)

/** Gruppiert nach Person (Groß-/Kleinschreibung egal), alphabetisch, „ohne Zuständigkeit“ zuletzt. */
private fun groupByOwner(list: List<Pair<Meeting, TaskItem>>): List<OwnerGroup> {
    val groups = LinkedHashMap<String, MutableList<Pair<Meeting, TaskItem>>>()
    val names = HashMap<String, String>()
    list.forEach { pair ->
        val owner = pair.second.owner.trim()
        val key = if (isOpenValue(owner)) "" else owner.lowercase()
        names.putIfAbsent(key, if (key.isEmpty()) NO_OWNER else owner)
        groups.getOrPut(key) { mutableListOf() }.add(pair)
    }
    return groups.entries
        .sortedWith(compareBy<Map.Entry<String, MutableList<Pair<Meeting, TaskItem>>>> { it.key.isEmpty() }
            .thenBy { it.key })
        .map { (key, tasks) ->
            // Überfällige/frühe Termine zuerst, Aufgaben ohne Datum danach (neueste Besprechung zuerst)
            val sorted = tasks.sortedWith(
                compareBy<Pair<Meeting, TaskItem>> { parseDueDate(it.second.due) ?: Long.MAX_VALUE }
                    .thenByDescending { it.first.createdAt }
            )
            OwnerGroup(names[key] ?: NO_OWNER, sorted)
        }
}

/**
 * Inhalt des Reiters „Aufgaben“ auf der Startseite: alle offenen Aufgaben aller Besprechungen,
 * nach Person gruppiert.
 */
fun LazyListScope.allTasksItems(
    open: List<Pair<Meeting, TaskItem>>,
    done: List<Pair<Meeting, TaskItem>>,
    showDone: Boolean,
    onShowDoneChange: (Boolean) -> Unit,
    onToggle: (Meeting, TaskItem) -> Unit,
    onOpen: (Meeting) -> Unit,
) {
    val overdue = open.count { dueState(it.second.due) == DueState.OVERDUE }
    item(key = "tasksSummary") {
        TasksSummaryRow(
            openCount = open.size,
            overdue = overdue,
            showDone = showDone,
            onShowDoneChange = onShowDoneChange,
        )
    }
    if (open.isEmpty()) {
        item(key = "tasksEmpty") {
            EmptyState(
                icon = Icons.Filled.TaskAlt,
                title = if (done.isEmpty()) "Noch keine Aufgaben" else "Alles erledigt",
                message = if (done.isEmpty()) {
                    "Aufgaben aus Ihren Besprechungen erscheinen hier gesammelt – nach Person sortiert, " +
                        "mit Termin. Abhaken genügt."
                } else {
                    "Alle vereinbarten Aufgaben sind abgehakt."
                },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    } else {
        groupByOwner(open).forEachIndexed { gi, group ->
            item(key = "owner_$gi") {
                OwnerHeader(group.name, group.tasks.size, isNone = group.name == NO_OWNER)
            }
            itemsIndexed(group.tasks, key = { i, _ -> "ot_${gi}_$i" }) { _, (m, t) ->
                OverviewTaskCard(m, t, onToggle = { onToggle(m, t) }, onOpen = { onOpen(m) })
            }
        }
    }
    if (showDone && done.isNotEmpty()) {
        item(key = "doneHeader") {
            Text(
                "Erledigt (${done.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 2.dp),
            )
        }
        itemsIndexed(done, key = { i, _ -> "dt_$i" }) { _, (m, t) ->
            OverviewTaskCard(m, t, onToggle = { onToggle(m, t) }, onOpen = { onOpen(m) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksSummaryRow(
    openCount: Int,
    overdue: Int,
    showDone: Boolean,
    onShowDoneChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                when (openCount) {
                    0 -> "Keine offenen Aufgaben"
                    1 -> "1 offene Aufgabe"
                    else -> "$openCount offene Aufgaben"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (overdue > 0) {
                Text(
                    if (overdue == 1) "1 überfällig" else "$overdue überfällig",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        FilterChip(
            selected = showDone,
            onClick = { onShowDoneChange(!showDone) },
            label = { Text("Erledigte anzeigen") },
            leadingIcon = if (showDone) {
                { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else null,
        )
    }
}

@Composable
private fun OwnerHeader(name: String, count: Int, isNone: Boolean) {
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isNone) {
            SpeakerAvatar(name = name, id = name.lowercase().hashCode() and 0xFFFF, size = 26.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = if (isNone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OverviewTaskCard(m: Meeting, task: TaskItem, onToggle: () -> Unit, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (task.done) cs.surfaceContainerLow else cs.surfaceContainerLowest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (task.done) 0.dp else 1.dp),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 14.dp, top = 6.dp, bottom = 12.dp)) {
            Checkbox(checked = task.done, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(top = 12.dp)) {
                Text(
                    task.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (task.done) cs.onSurfaceVariant else cs.onSurface,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                )
                val note = task.doneNote
                if (task.done && !note.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(note, style = MaterialTheme.typography.bodySmall, color = successColor())
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!isOpenValue(task.due) || !task.done) DueChip(task.due, done = task.done)
                    SourceMeetingChip(m)
                }
            }
        }
    }
}

@Composable
private fun SourceMeetingChip(m: Meeting) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = cs.surfaceContainerHigh,
        contentColor = cs.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                "${m.title.ifBlank { "Besprechung" }.take(28)} · ${formatDateShort(m.createdAt)}",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
