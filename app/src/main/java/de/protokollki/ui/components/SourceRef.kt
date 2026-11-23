package de.protokollki.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.protokollki.data.Meeting

/**
 * Unaufdringlicher Quellenbeleg „▶ 12:34“. Zeigt nichts, wenn [ref] keinen gültigen
 * Redebeitrag bezeichnet.
 */
@Composable
fun SourceChip(m: Meeting, ref: Int, onCite: (Int) -> Unit, modifier: Modifier = Modifier) {
    val seg = if (ref >= 0) m.segments.getOrNull(ref) else null
    if (seg == null) return
    val cs = MaterialTheme.colorScheme
    val audio = !m.audioDeleted
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(cs.primary.copy(alpha = 0.08f))
            .clickable { onCite(ref) }
            .padding(start = 5.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (audio) Icons.Filled.PlayArrow else Icons.Filled.FormatQuote,
            contentDescription = if (audio) "Quelle abspielen" else "Quelle anzeigen",
            tint = cs.primary,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(
            formatDuration(seg.start),
            style = MaterialTheme.typography.labelSmall,
            color = cs.primary,
            maxLines = 1,
        )
    }
}

/** Karte „Zitat“: zeigt den belegenden Redebeitrag kurz am unteren Rand. */
@Composable
fun QuoteCard(
    m: Meeting,
    ref: Int,
    onShowInTranscript: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seg = m.segments.getOrNull(ref) ?: return
    val cs = MaterialTheme.colorScheme
    val name = m.speakerName(seg.speakerId)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = cs.inverseSurface,
        contentColor = cs.inverseOnSurface,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.FormatQuote, contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = cs.inversePrimary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Zitat · ",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.inverseOnSurface.copy(alpha = 0.7f),
                )
                SpeakerAvatar(name = name, id = seg.speakerId, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.inverseOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    formatDuration(seg.start),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.inverseOnSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Schließen", modifier = Modifier.size(18.dp))
                }
            }
            Text(
                "„${seg.text.trim()}“",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 12.dp),
            )
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onShowInTranscript) {
                    Text("Im Transkript zeigen", color = cs.inversePrimary)
                }
            }
        }
    }
}
