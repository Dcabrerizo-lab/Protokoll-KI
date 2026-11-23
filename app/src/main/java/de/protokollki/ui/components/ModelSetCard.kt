package de.protokollki.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import de.protokollki.engine.ModelSet
import de.protokollki.ui.theme.successColor

/** Auswahlkarte für ein KI-Modellpaket (Onboarding + Einstellungen). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSetCard(
    set: ModelSet,
    selected: Boolean,
    recommended: Boolean,
    installed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val border by animateColorAsState(
        if (selected) cs.primary else cs.outlineVariant, label = "modelBorder"
    )
    val bg by animateColorAsState(
        if (selected) cs.primaryContainer.copy(alpha = 0.45f) else cs.surfaceContainerLowest,
        label = "modelBg",
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = bg,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(set.label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatGb(set.downloadBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
                Text(
                    "(${set.fitsFor})",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    set.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                if (recommended || installed || selected) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (recommended) {
                            TinyBadge("Empfohlen", Icons.Filled.Star, cs.tertiary)
                        }
                        if (installed) {
                            TinyBadge("Installiert", Icons.Filled.CheckCircle, successColor())
                        } else if (selected) {
                            TinyBadge("Download nötig", Icons.Filled.CloudDownload, cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TinyBadge(text: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}
