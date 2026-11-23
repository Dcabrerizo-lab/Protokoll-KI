package de.protokollki.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.protokollki.data.Meeting
import de.protokollki.data.MeetingStatus
import de.protokollki.ui.theme.RecordRed
import de.protokollki.ui.theme.speakerColor
import de.protokollki.ui.theme.successColor

/** Runder Avatar mit Initialen in der Sprecherfarbe. */
@Composable
fun SpeakerAvatar(
    name: String,
    id: Int,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(speakerColor(id)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(name),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.38f).sp,
            maxLines = 1,
        )
    }
}

/** Überlappende kleine Avatare (z. B. in der Besprechungsliste). */
@Composable
fun SpeakerAvatarStack(
    speakers: List<Pair<Int, String>>,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    max: Int = 4,
) {
    if (speakers.isEmpty()) return
    val shown = speakers.take(max)
    val ringColor = MaterialTheme.colorScheme.surfaceContainerLowest
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box {
            shown.forEachIndexed { i, (id, name) ->
                SpeakerAvatar(
                    name = name,
                    id = id,
                    size = size,
                    modifier = Modifier
                        .padding(start = (size.value * 0.7f * i).dp)
                        .border(1.5.dp, ringColor, CircleShape),
                )
            }
        }
        if (speakers.size > max) {
            Spacer(Modifier.width(6.dp))
            Text(
                "+${speakers.size - max}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private class ChipLook(
    val text: String,
    val bg: Color,
    val fg: Color,
    val icon: ImageVector?,
    val spinner: Boolean,
)

/** Kleiner Status-Chip für eine Besprechung. */
@Composable
fun StatusChip(meeting: Meeting, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val success = successColor()
    val pct = (meeting.progress.coerceIn(0f, 1f) * 100).toInt()
    val look = when (meeting.status) {
        MeetingStatus.RECORDING -> ChipLook("Aufnahme läuft", RecordRed.copy(alpha = 0.14f), RecordRed, Icons.Filled.FiberManualRecord, false)
        MeetingStatus.RECORDED -> ChipLook("Nicht verarbeitet", cs.surfaceContainerHighest, cs.onSurfaceVariant, Icons.Filled.PlayCircleOutline, false)
        MeetingStatus.QUEUED -> ChipLook("Wartet …", cs.secondaryContainer, cs.onSecondaryContainer, Icons.Filled.HourglassTop, false)
        MeetingStatus.TRANSCRIBING -> ChipLook("Transkription $pct %", cs.primaryContainer, cs.onPrimaryContainer, null, true)
        MeetingStatus.SUMMARIZING -> ChipLook("Protokoll $pct %", cs.primaryContainer, cs.onPrimaryContainer, null, true)
        MeetingStatus.DONE -> ChipLook("Fertig", success.copy(alpha = 0.14f), success, Icons.Filled.CheckCircle, false)
        MeetingStatus.ERROR -> ChipLook("Fehler", cs.errorContainer, cs.onErrorContainer, Icons.Filled.ErrorOutline, false)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = look.bg,
        contentColor = look.fg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (look.spinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.5.dp,
                    color = look.fg,
                )
            } else if (look.icon != null) {
                Icon(look.icon, contentDescription = null, modifier = Modifier.size(13.dp))
            }
            Text(look.text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** Abschnittsüberschrift mit optionalem Symbol. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) trailing()
    }
}

/** Kleines „Offline“-Abzeichen (Datenschutz-Versprechen). */
@Composable
fun OfflineBadge(modifier: Modifier = Modifier, label: String = "Offline") {
    val c = successColor()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = c.copy(alpha = 0.12f),
        contentColor = c,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Illustration aus konzentrischen Kreisen mit Symbol in der Mitte. */
@Composable
fun IllustrationIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.06f))
        )
        Box(
            Modifier
                .size(size * 0.74f)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.10f))
        )
        Box(
            Modifier
                .size(size * 0.5f)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.75f)))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(size * 0.24f),
            )
        }
    }
}

/** Freundlicher Leerzustand. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IllustrationIcon(icon = icon, size = 112.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** Kleiner Info-Chip mit Symbol (Datum, Dauer, …). */
@Composable
fun MetaItem(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Sprecherliste einer Besprechung (aus speakers oder aus den Segmenten abgeleitet). */
fun speakersOf(m: Meeting): List<Pair<Int, String>> {
    val ids = (m.speakers.map { it.id } + m.segments.map { it.speakerId }).distinct().sorted()
    return ids.map { it to m.speakerName(it) }
}
