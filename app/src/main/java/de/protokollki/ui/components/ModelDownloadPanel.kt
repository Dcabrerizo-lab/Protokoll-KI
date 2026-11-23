package de.protokollki.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import de.protokollki.engine.ModelState
import de.protokollki.ui.theme.WarningAmber
import de.protokollki.ui.theme.successColor

/**
 * Download-Bereich für das gewählte Modellpaket: Knopf, Fortschritt, Fehler mit Wiederholen
 * und Erfolgsmeldung. Daten kommen aus [ModelState].
 */
@Composable
fun ModelDownloadPanel(
    state: ModelState,
    freeBytes: Long,
    missingBytes: Long,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val progress by animateFloatAsState(state.progress.coerceIn(0f, 1f), label = "dl")
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            state.installed && !state.downloading -> {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = successColor().copy(alpha = 0.12f),
                    contentColor = successColor(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Modelle installiert – die App arbeitet komplett offline.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            state.downloading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.status.ifBlank { "Download wird vorbereitet …" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(progress * 100).toInt()} %",
                        style = MaterialTheme.typography.titleSmall,
                        color = cs.primary,
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                    strokeCap = StrokeCap.Round,
                    trackColor = cs.surfaceContainerHighest,
                )
                Text(
                    "Sie können die App währenddessen verlassen – der Download läuft im Hintergrund weiter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Download abbrechen")
                }
            }

            else -> {
                AnimatedVisibility(visible = state.error != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = cs.errorContainer,
                        contentColor = cs.onErrorContainer,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text(state.error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (missingBytes > 0 && freeBytes < missingBytes * 1.3) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Warning, contentDescription = null,
                            tint = WarningAmber, modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Wenig freier Speicher: ${formatGb(freeBytes)} frei, " +
                                "benötigt ca. ${formatGb((missingBytes * 1.3).toLong())}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(
                        if (state.error != null) Icons.Filled.Refresh else Icons.Filled.CloudDownload,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (state.error != null) "Erneut versuchen"
                        else "Modelle herunterladen (einmalig, WLAN empfohlen)",
                    )
                }
                if (missingBytes > 0) {
                    Text(
                        "Download: ca. ${formatGb(missingBytes)} · danach nie wieder Internet nötig",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
