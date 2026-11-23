package de.protokollki.ui.components

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.protokollki.tts.ReadAloud

/** Gewählte Vorlese-Geschwindigkeit (gilt app-weit, solange die App läuft). */
object ReadAloudUi {
    var speed by mutableFloatStateOf(1f)

    fun nextSpeed(): Float {
        val next = when (speed) {
            1f -> 1.25f
            1.25f -> 1.5f
            else -> 1f
        }
        speed = next
        return next
    }

    fun label(s: Float): String = when (s) {
        1f -> "1×"
        1.25f -> "1,25×"
        1.5f -> "1,5×"
        else -> "${s}×"
    }
}

const val NO_GERMAN_VOICE_MESSAGE =
    "Bitte in den Android-Einstellungen eine deutsche Sprachausgabe installieren."

/**
 * Kompakte Vorlese-Leiste (Pause/Weiter, ±1 Satz, Tempo, Stopp).
 * Erscheint nur, solange [ReadAloud] aktiv ist.
 */
@Composable
fun ReadAloudBar(modifier: Modifier = Modifier, navigationPadding: Boolean = false) {
    val st by ReadAloud.state.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = st.active,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        val cs = MaterialTheme.colorScheme
        val total = st.total
        val current = (st.index + 1).coerceIn(0, total.coerceAtLeast(0))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (navigationPadding) Modifier.navigationBarsPadding() else Modifier)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(20.dp),
            color = cs.primaryContainer,
            contentColor = cs.onPrimaryContainer,
            shadowElevation = 4.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${st.title}".ifBlank { "Vorlesen" },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            (if (st.paused) "Pausiert · " else "Vorlesen · ") +
                                (if (total > 0) "Satz $current von $total" else "wird vorbereitet …"),
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onPrimaryContainer.copy(alpha = 0.75f),
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = { ReadAloud.skip(-1) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "Vorheriger Satz")
                    }
                    FilledIconButton(
                        onClick = { if (st.paused) ReadAloud.resume() else ReadAloud.pause() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            if (st.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (st.paused) "Weiter vorlesen" else "Pause",
                        )
                    }
                    IconButton(onClick = { ReadAloud.skip(1) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Nächster Satz")
                    }
                    TextButton(
                        onClick = { ReadAloud.setSpeed(ReadAloudUi.nextSpeed()) },
                        modifier = Modifier.width(56.dp),
                    ) {
                        Text(
                            ReadAloudUi.label(ReadAloudUi.speed),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = { ReadAloud.stop() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Vorlesen beenden")
                    }
                }
                LinearProgressIndicator(
                    progress = { if (total > 0) current.toFloat() / total else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 8.dp)
                        .height(3.dp),
                    strokeCap = StrokeCap.Round,
                    trackColor = cs.onPrimaryContainer.copy(alpha = 0.12f),
                )
            }
        }
    }
}

/** Startet das Vorlesen einer Besprechung mit der zuletzt gewählten Geschwindigkeit. */
fun startReadAloud(context: Context, m: de.protokollki.data.Meeting) {
    val app = context.applicationContext
    runCatching { ReadAloud.setSpeed(ReadAloudUi.speed) }
    ReadAloud.speakMeeting(app, m)
    runCatching { ReadAloud.setSpeed(ReadAloudUi.speed) }
}

/** Fehlerhinweis fürs Vorlesen (z. B. keine deutsche Stimme installiert). */
@Composable
fun ReadAloudErrorDialog(message: String, onDismiss: () -> Unit, context: Context) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) },
        title = { Text("Vorlesen nicht möglich") },
        text = { Text(message.ifBlank { NO_GERMAN_VOICE_MESSAGE }) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                openTtsSettings(context)
            }) { Text("Sprachausgabe öffnen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

private fun openTtsSettings(context: Context) {
    val intents = listOf(
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (i in intents) {
        val ok = runCatching {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (ok) return
    }
}
