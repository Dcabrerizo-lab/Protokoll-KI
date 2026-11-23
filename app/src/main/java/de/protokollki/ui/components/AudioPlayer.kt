package de.protokollki.ui.components

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.File

/** Einfacher Wrapper um [MediaPlayer] mit Compose-Zuständen. */
@Stable
class PlayerController(private val path: String) {
    private var mp: MediaPlayer? = null

    var ready by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    var speed by mutableFloatStateOf(1f)
        private set

    fun prepare() {
        if (mp != null) return
        try {
            val p = MediaPlayer()
            p.setDataSource(path)
            p.prepare()
            p.setOnCompletionListener {
                playing = false
                positionMs = durationMs
            }
            mp = p
            durationMs = p.duration.coerceAtLeast(0)
            ready = true
        } catch (e: Exception) {
            ready = false
        }
    }

    fun toggle() = if (playing) pause() else play()

    fun play() {
        val p = mp ?: return
        try {
            if (durationMs > 0 && positionMs >= durationMs - 100) {
                p.seekTo(0); positionMs = 0
            }
            p.start()
            playing = true
            applySpeed()
        } catch (_: Exception) {
            playing = false
        }
    }

    fun pause() {
        try { mp?.pause() } catch (_: Exception) {}
        playing = false
    }

    fun seekTo(ms: Int) {
        val target = ms.coerceIn(0, durationMs.coerceAtLeast(0))
        try { mp?.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST) } catch (_: Exception) {}
        positionMs = target
    }

    fun skip(deltaMs: Int) = seekTo(positionMs + deltaMs)

    /** Springt zu einer Stelle (Sekunden) und spielt ab. */
    fun playFrom(sec: Float) {
        seekTo((sec * 1000).toInt())
        if (!playing) play()
    }

    fun cycleSpeed() {
        speed = when (speed) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        if (playing) applySpeed()   // im Pausenzustand würde das Setzen die Wiedergabe starten
    }

    private fun applySpeed() {
        val p = mp ?: return
        try {
            p.playbackParams = p.playbackParams.setSpeed(speed)
        } catch (_: Exception) {}
    }

    fun tick() {
        val p = mp ?: return
        if (playing) {
            try { positionMs = p.currentPosition } catch (_: Exception) {}
        }
    }

    fun release() {
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        ready = false
        playing = false
    }
}

/**
 * Erzeugt einen Player für [path], sobald [enabled] (Datei vorhanden, Aufnahme beendet).
 * Wird beim Verlassen freigegeben.
 */
@Composable
fun rememberPlayer(path: String, enabled: Boolean): PlayerController? {
    val player = remember(path, enabled) {
        if (enabled && File(path).let { it.exists() && it.length() > 44 }) PlayerController(path) else null
    }
    DisposableEffect(player) {
        player?.prepare()
        onDispose { player?.release() }
    }
    val playing = player?.playing == true
    LaunchedEffect(player, playing) {
        while (player != null && player.playing) {
            player.tick()
            delay(150)
        }
    }
    return player
}

private fun speedLabel(s: Float) = when (s) {
    1f -> "1×"
    1.5f -> "1,5×"
    2f -> "2×"
    else -> "${s}×"
}

/** Wiedergabeleiste am unteren Bildschirmrand. */
@Composable
fun PlayerBar(player: PlayerController, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = player.durationMs.coerceAtLeast(1)
    val shownPos = if (dragging) dragValue else player.positionMs.toFloat() / duration

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
        ) {
            Slider(
                value = shownPos.coerceIn(0f, 1f),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    player.seekTo((dragValue * duration).toInt())
                    dragging = false
                },
                enabled = player.ready,
                modifier = Modifier.fillMaxWidth().height(32.dp),
                colors = SliderDefaults.colors(
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            )
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(
                    formatDuration((if (dragging) dragValue * duration else player.positionMs.toFloat()) / 1000f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatDuration(player.durationMs / 1000f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Spacer(Modifier.width(64.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconButton(onClick = { player.skip(-10_000) }, enabled = player.ready) {
                        Icon(Icons.Filled.Replay10, contentDescription = "10 Sekunden zurück")
                    }
                    FilledIconButton(
                        onClick = { player.toggle() },
                        enabled = player.ready,
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            if (player.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (player.playing) "Pause" else "Abspielen",
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    IconButton(onClick = { player.skip(10_000) }, enabled = player.ready) {
                        Icon(Icons.Filled.Forward10, contentDescription = "10 Sekunden vor")
                    }
                }
                Box(modifier = Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = { player.cycleSpeed() }, enabled = player.ready) {
                        Text(speedLabel(player.speed), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
