package de.protokollki.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.protokollki.data.Meeting
import de.protokollki.data.Segment
import de.protokollki.data.TaskItem
import de.protokollki.data.Topic as TopicData
import de.protokollki.ui.components.EmptyState
import de.protokollki.ui.components.SectionHeader
import de.protokollki.ui.components.SpeakerAvatar
import de.protokollki.ui.components.formatDuration
import de.protokollki.ui.theme.speakerColor
import de.protokollki.ui.theme.successColor

private val ItemPadding = Modifier.padding(horizontal = 16.dp)

// ================================================================ Zusammenfassung

fun LazyListScope.summaryItems(
    m: Meeting,
    canGenerate: Boolean,
    onGenerate: () -> Unit,
) {
    val s = m.summary
    if (s.isEmpty) {
        item(key = "sumEmpty") {
            EmptyState(
                icon = Icons.Filled.AutoAwesome,
                title = if (m.isBusy) "Protokoll wird erstellt …" else "Noch keine Zusammenfassung",
                message = when {
                    m.isBusy -> "Die KI arbeitet gerade offline auf Ihrem Gerät. Das Ergebnis erscheint hier automatisch."
                    m.segments.isEmpty() -> "Sobald das Transkript fertig ist, entsteht hier das Ergebnisprotokoll mit Themen, Beschlüssen und offenen Punkten."
                    else -> "Für diese Besprechung gibt es noch kein Ergebnisprotokoll."
                },
                action = if (canGenerate && m.segments.isNotEmpty() && !m.isBusy) {
                    {
                        FilledTonalButton(onClick = onGenerate) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Zusammenfassung erstellen")
                        }
                    }
                } else null,
            )
        }
        return
    }

    if (s.overview.isNotBlank()) {
        item(key = "overview") { OverviewCard(s.overview) }
    }
    if (s.topics.isNotEmpty()) {
        item(key = "topicsHeader") {
            SectionHeader("Themen", icon = Icons.Filled.Topic, modifier = ItemPadding.padding(top = 12.dp))
        }
        itemsIndexed(s.topics, key = { i, _ -> "topic_$i" }) { i, t -> TopicCard(i + 1, t) }
    }
    if (s.decisions.isNotEmpty()) {
        item(key = "decHeader") {
            SectionHeader("Beschlüsse", icon = Icons.Filled.Gavel, modifier = ItemPadding.padding(top = 12.dp))
        }
        item(key = "decisions") { NumberedCard(s.decisions, MaterialTheme.colorScheme.primary) }
    }
    if (s.openPoints.isNotEmpty()) {
        item(key = "openHeader") {
            SectionHeader("Offene Punkte", icon = Icons.Filled.Flag, modifier = ItemPadding.padding(top = 12.dp))
        }
        item(key = "openPoints") { BulletCard(s.openPoints, MaterialTheme.colorScheme.tertiary) }
    }
}

@Composable
private fun OverviewCard(text: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = ItemPadding
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(cs.primaryContainer, cs.secondaryContainer.copy(alpha = 0.7f))
                )
            )
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome, contentDescription = null,
                    tint = cs.onPrimaryContainer, modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Überblick",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, color = cs.onPrimaryContainer)
        }
    }
}

@Composable
private fun TopicCard(number: Int, topic: TopicData) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = ItemPadding.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(cs.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$number",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSecondaryContainer,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(topic.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            if (topic.points.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                topic.points.forEach { p -> BulletLine(p, cs.secondary) }
            }
        }
    }
}

@Composable
private fun BulletLine(text: String, dot: Color) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 9.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NumberedCard(lines: List<String>, accent: Color) {
    Card(
        modifier = ItemPadding.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            lines.forEachIndexed { i, line ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BulletCard(lines: List<String>, accent: Color) {
    Card(
        modifier = ItemPadding.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            lines.forEach { BulletLine(it, accent) }
        }
    }
}

// ================================================================ Transkript

fun LazyListScope.transcriptItems(
    m: Meeting,
    activeIndex: Int,
    verbatim: Boolean,
    onSeek: (Segment) -> Unit,
    onLongPress: (Int) -> Unit,
) {
    if (m.segments.isEmpty()) {
        item(key = "trEmpty") {
            EmptyState(
                icon = Icons.Filled.Forum,
                title = if (m.isBusy) "Transkript entsteht …" else "Noch kein Transkript",
                message = if (m.isBusy) "Die Sprache wird gerade offline in Text umgewandelt."
                else "Verarbeiten Sie die Aufnahme, um ein Transkript mit Sprechern zu erhalten.",
            )
        }
        return
    }
    item(key = "trHint") {
        Row(
            modifier = ItemPadding.padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.GraphicEq, contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                (if (verbatim) "Wortprotokoll · " else "") + "${m.segments.size} Redebeiträge · " +
                    "Tippen zum Abspielen, lange drücken zum Ändern des Sprechers",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    itemsIndexed(m.segments, key = { i, _ -> "seg_$i" }) { i, seg ->
        val prev = m.segments.getOrNull(i - 1)
        val newSpeaker = prev == null || prev.speakerId != seg.speakerId
        SegmentRow(
            segment = seg,
            name = m.speakerName(seg.speakerId),
            showHeader = newSpeaker,
            active = i == activeIndex,
            verbatim = verbatim,
            onClick = { onSeek(seg) },
            onLongClick = { onLongPress(i) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentRow(
    segment: Segment,
    name: String,
    showHeader: Boolean,
    active: Boolean,
    verbatim: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val color = speakerColor(segment.speakerId)
    val bg by animateColorAsState(
        if (active) color.copy(alpha = 0.14f) else Color.Transparent, label = "segBg"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(top = if (showHeader) 10.dp else 0.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(36.dp)) {
            if (showHeader) SpeakerAvatar(name = name, id = segment.speakerId, size = 34.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (showHeader) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleSmall,
                        color = color,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatDuration(segment.start),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
            }
            Text(
                segment.text.trim(),
                style = if (verbatim) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                color = if (active) cs.onSurface else cs.onSurface.copy(alpha = 0.92f),
            )
        }
    }
}

// ================================================================ Aufgaben

fun LazyListScope.taskItems(
    m: Meeting,
    onToggle: (Int) -> Unit,
) {
    if (m.tasks.isEmpty()) {
        item(key = "taskEmpty") {
            EmptyState(
                icon = Icons.Filled.TaskAlt,
                title = if (m.isBusy) "Aufgaben werden ermittelt …" else "Keine Aufgaben erkannt",
                message = if (m.isBusy) "Die KI sucht im Gespräch nach Vereinbarungen: wer macht was bis wann."
                else "In dieser Besprechung wurden keine konkreten Aufgaben vereinbart.",
            )
        }
        return
    }
    item(key = "taskProgress") {
        val done = m.tasks.count { it.done }
        val total = m.tasks.size
        Column(ItemPadding.padding(bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$done von $total erledigt",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${if (total == 0) 0 else done * 100 / total} %",
                    style = MaterialTheme.typography.labelLarge,
                    color = successColor(),
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done.toFloat() / total },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = successColor(),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round,
            )
        }
    }
    itemsIndexed(m.tasks, key = { i, _ -> "task_$i" }) { i, t ->
        TaskCard(task = t, onToggle = { onToggle(i) })
    }
}

private fun isOpenValue(v: String) = v.isBlank() || v.trim().equals("offen", ignoreCase = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskCard(task: TaskItem, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        onClick = onToggle,
        modifier = ItemPadding.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (task.done) cs.surfaceContainerLow else cs.surfaceContainerLowest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (task.done) 0.dp else 1.dp),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 14.dp)) {
            Checkbox(checked = task.done, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(top = 12.dp)) {
                Text(
                    task.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (task.done) cs.onSurfaceVariant else cs.onSurface,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniChip(
                        icon = Icons.Filled.Person,
                        text = if (isOpenValue(task.owner)) "Verantwortlich: offen" else task.owner,
                        muted = isOpenValue(task.owner),
                    )
                    MiniChip(
                        icon = Icons.Filled.CalendarToday,
                        text = if (isOpenValue(task.due)) "Termin: offen" else task.due,
                        muted = isOpenValue(task.due),
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniChip(icon: ImageVector, text: String, muted: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (muted) cs.surfaceContainerHigh else cs.primaryContainer.copy(alpha = 0.7f),
        contentColor = if (muted) cs.onSurfaceVariant else cs.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}
