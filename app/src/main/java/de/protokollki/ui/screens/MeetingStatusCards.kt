package de.protokollki.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.protokollki.data.Meeting
import de.protokollki.data.MeetingStatus
import de.protokollki.ui.theme.RecordRed

/** Statusbereich oberhalb der Tabs (Aufnahme, bereit, Verarbeitung, Fehler). */
@Composable
fun StatusSection(
    m: Meeting,
    modelsInstalled: Boolean,
    recordingThis: Boolean,
    onOpenRecording: () -> Unit,
    onProcess: (speakerCount: Int) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
    onSendToPc: (() -> Unit)? = null,
) {
    when {
        m.status == MeetingStatus.RECORDING -> RecordingCard(recordingThis, onOpenRecording, modifier)
        m.status == MeetingStatus.RECORDED ->
            ReadyCard(m, modelsInstalled, onProcess, onOpenModels, modifier, onSendToPc)
        m.isBusy -> ProcessingCard(m, onCancel, modifier)
        m.status == MeetingStatus.ERROR -> ErrorCard(m, modelsInstalled, onRetry, modifier)
        else -> Unit
    }
}

@Composable
private fun StatusCardShell(
    modifier: Modifier,
    container: Color,
    border: Color? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = container,
        border = if (border != null) BorderStroke(1.dp, border) else null,
    ) {
        Box(Modifier.padding(20.dp)) { content() }
    }
}

@Composable
private fun RecordingCard(recordingThis: Boolean, onOpenRecording: () -> Unit, modifier: Modifier) {
    StatusCardShell(modifier, RecordRed.copy(alpha = 0.10f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = RecordRed)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (recordingThis) "Aufnahme läuft" else "Aufnahme wird gespeichert …",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (recordingThis) "Nach dem Beenden wird die Besprechung hier verarbeitet."
                    else "Einen Moment – die Audiodatei wird abgeschlossen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (recordingThis) {
                Button(onClick = onOpenRecording) { Text("Öffnen") }
            }
        }
    }
}

@Composable
private fun ReadyCard(
    m: Meeting,
    modelsInstalled: Boolean,
    onProcess: (Int) -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier,
    onSendToPc: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    var count by remember(m.id) { mutableIntStateOf(m.expectedSpeakers) }
    var names by remember(m.id) { mutableStateOf(m.participants.joinToString(", ")) }
    // Namen vorab: werden richtig geschrieben und helfen, Aufgaben der richtigen Person zuzuordnen
    val start: () -> Unit = {
        val list = names.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        de.protokollki.data.MeetingRepository.update(m.id) { it.copy(participants = list) }
        onProcess(count)
    }
    StatusCardShell(modifier, cs.surfaceContainerLow, cs.outlineVariant) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = cs.onPrimaryContainer)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Bereit zur Verarbeitung", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Transkript, Sprecher, Protokoll und Aufgaben – komplett offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(16.dp), tint = cs.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("Anzahl Teilnehmender", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            SpeakerCountSelector(value = count, onChange = { count = it })
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = names,
                onValueChange = { names = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Namen der Teilnehmenden (optional)") },
                placeholder = { Text("z. B. Frau Schmidt, Herr Yilmaz") },
                supportingText = { Text("Mit Komma trennen. Verbessert Schreibweise und Zuordnung der Aufgaben.") },
                singleLine = false,
                maxLines = 3,
            )
            Spacer(Modifier.height(12.dp))
            if (modelsInstalled) {
                Button(
                    onClick = start,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Jetzt verarbeiten")
                }
            } else {
                OutlinedButton(
                    onClick = onOpenModels,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Zuerst KI-Modelle herunterladen")
                }
            }
            if (onSendToPc != null) {
                TextButton(
                    onClick = onSendToPc,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Icon(Icons.Filled.Computer, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Lieber am PC auswerten")
                }
            }
        }
    }
}

private data class Phase(val label: String, val icon: ImageVector)

private val Phases = listOf(
    Phase("Sprecher", Icons.Filled.RecordVoiceOver),
    Phase("Transkription", Icons.Filled.GraphicEq),
    Phase("Protokoll", Icons.Filled.Description),
    Phase("Aufgaben", Icons.Filled.TaskAlt),
)

/** Aktuelle Phase (0..3) aus Status und Fortschrittstext; -1 = Warteschlange. */
private fun phaseIndex(m: Meeting): Int {
    val t = m.progressText.lowercase()
    return when (m.status) {
        MeetingStatus.QUEUED -> -1
        MeetingStatus.TRANSCRIBING -> if (t.contains("transkrib")) 1 else 0
        MeetingStatus.SUMMARIZING -> if (t.contains("aufgabe")) 3 else 2
        else -> 0
    }
}

@Composable
private fun ProcessingCard(m: Meeting, onCancel: () -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val phase = phaseIndex(m)
    val progress by animateFloatAsState(m.progress.coerceIn(0f, 1f), label = "proc")
    StatusCardShell(modifier, cs.primaryContainer.copy(alpha = 0.35f), cs.primary.copy(alpha = 0.25f)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (phase < 0) "In der Warteschlange" else "Wird verarbeitet …",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(11.dp), tint = cs.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Offline auf Ihrem Gerät · App darf geschlossen werden",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "${(progress * 100).toInt()} %",
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.primary,
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth()) {
                Phases.forEachIndexed { i, p ->
                    PhaseStep(
                        phase = p,
                        state = when {
                            i < phase -> 2
                            i == phase -> 1
                            else -> 0
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                strokeCap = StrokeCap.Round,
                trackColor = cs.surfaceContainerHighest,
            )
            if (m.progressText.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(m.progressText, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                Text("Abbrechen")
            }
        }
    }
}

/** state: 0 = ausstehend, 1 = läuft, 2 = fertig */
@Composable
private fun PhaseStep(phase: Phase, state: Int, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "phase")
    val s by pulse.animateFloat(
        initialValue = 0.92f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "phaseScale",
    )
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val bg = when (state) {
            2 -> cs.primary
            1 -> cs.primaryContainer
            else -> Color.Transparent
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .scale(if (state == 1) s else 1f)
                .clip(CircleShape)
                .background(bg)
                .border(
                    width = if (state == 0) 1.5.dp else 0.dp,
                    color = if (state == 0) cs.outlineVariant else Color.Transparent,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state == 2) Icons.Filled.Check else phase.icon,
                contentDescription = null,
                tint = when (state) {
                    2 -> cs.onPrimary
                    1 -> cs.onPrimaryContainer
                    else -> cs.outline
                },
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            phase.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (state == 0) cs.onSurfaceVariant else cs.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun ErrorCard(m: Meeting, modelsInstalled: Boolean, onRetry: () -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    StatusCardShell(modifier, cs.errorContainer) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = cs.onErrorContainer)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Verarbeitung fehlgeschlagen", style = MaterialTheme.typography.titleMedium, color = cs.onErrorContainer)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        m.error ?: "Unbekannter Fehler.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onErrorContainer,
                    )
                }
            }
            if (modelsInstalled) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Erneut versuchen")
                    }
                }
            }
        }
    }
}
