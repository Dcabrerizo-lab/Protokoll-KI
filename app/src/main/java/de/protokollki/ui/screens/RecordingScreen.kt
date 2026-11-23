package de.protokollki.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.protokollki.audio.Recorder
import de.protokollki.data.AppSettings
import de.protokollki.data.MeetingRepository
import de.protokollki.data.MeetingStatus
import de.protokollki.ui.components.formatClock
import de.protokollki.ui.hasPermission
import de.protokollki.ui.openAppSettings
import de.protokollki.ui.theme.RecordRed
import de.protokollki.ui.theme.RecordingBgBottom
import de.protokollki.ui.theme.RecordingBgTop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow

@Composable
fun RecordingScreen(
    onFinished: (meetingId: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val rec by Recorder.state.collectAsStateWithLifecycle()

    // Bildschirm während der Aufnahme anlassen
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var meetingId by rememberSaveable { mutableStateOf(Recorder.state.value.meetingId) }
    var permissionDenied by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var sawActive by remember { mutableStateOf(false) }

    fun startRecording() {
        try {
            val id = Recorder.start(context)
            meetingId = id
            permissionDenied = false
            startError = if (Recorder.state.value.active) null
            else MeetingRepository.get(id)?.error ?: "Die Aufnahme konnte nicht gestartet werden."
        } catch (e: Exception) {
            startError = e.message ?: "Die Aufnahme konnte nicht gestartet werden."
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startRecording() else permissionDenied = true }

    LaunchedEffect(Unit) {
        val s = Recorder.state.value
        if (s.active) {
            meetingId = s.meetingId
        } else if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
            startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Aufnahme wurde von außen beendet (z. B. über die Benachrichtigung)
    LaunchedEffect(rec.active) {
        if (rec.active) {
            sawActive = true
            if (rec.meetingId != null) meetingId = rec.meetingId
        } else if (sawActive && !leaving) {
            leaving = true
            val id = meetingId
            if (id != null) onFinished(id) else onBack()
        }
    }

    val stop: () -> Unit = {
        if (!leaving) {
            leaving = true
            val id = rec.meetingId ?: meetingId
            runCatching { Recorder.stop(context) }
            if (id != null) onFinished(id) else onBack()
        }
    }

    val discard: () -> Unit = {
        if (!leaving) {
            leaving = true
            val id = rec.meetingId ?: meetingId
            val autoBefore = AppSettings.state.value.autoProcess
            // Verwerfen soll keine Verarbeitung auslösen
            if (autoBefore) AppSettings.update { it.copy(autoProcess = false) }
            runCatching { Recorder.stop(context) }
            CoroutineScope(Dispatchers.Main).launch {
                // Warten, bis der Aufnahme-Thread die Datei abgeschlossen hat
                var waited = 0
                while (id != null && waited < 6000 &&
                    MeetingRepository.get(id)?.status == MeetingStatus.RECORDING
                ) {
                    delay(100); waited += 100
                }
                delay(300)
                if (id != null) runCatching { MeetingRepository.delete(id) }
                if (autoBefore) AppSettings.update { it.copy(autoProcess = true) }
            }
            onBack()
        }
    }

    BackHandler { onBack() }   // Aufnahme läuft im Hintergrund weiter

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(RecordingBgTop, RecordingBgBottom)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Kopfzeile
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Minimieren",
                        tint = Color.White,
                    )
                }
                Text(
                    "Neue Aufnahme",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Lock, contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Nur auf diesem Gerät",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }

            if (permissionDenied || startError != null) {
                PermissionOrErrorView(
                    error = startError,
                    onRetry = {
                        if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) startRecording()
                        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onOpenSettings = { openAppSettings(context) },
                    onBack = onBack,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    StatusPill(active = rec.active, paused = rec.paused)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        formatClock(rec.elapsedMs),
                        color = Color.White,
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Light,
                        style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
                    )
                    Spacer(Modifier.height(40.dp))
                    Waveform(
                        levels = rec.levels,
                        paused = rec.paused || !rec.active,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(32.dp))
                    Text(
                        "Tipp: Legen Sie das Smartphone mittig zwischen die Teilnehmenden.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp),
                    )
                }

                // Bedienelemente
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 36.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top,
                ) {
                    RoundControl(
                        icon = Icons.Filled.Delete,
                        label = "Verwerfen",
                        enabled = rec.active,
                        onClick = { confirmDiscard = true },
                    )
                    StopControl(enabled = rec.active, onClick = stop)
                    AnimatedContent(targetState = rec.paused, label = "pauseResume") { paused ->
                        RoundControl(
                            icon = if (paused) Icons.Filled.Mic else Icons.Filled.Pause,
                            label = if (paused) "Fortsetzen" else "Pause",
                            enabled = rec.active,
                            onClick = { if (paused) Recorder.resume() else Recorder.pause() },
                        )
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Aufnahme verwerfen?") },
            text = { Text("Die bisherige Aufnahme wird gelöscht und kann nicht wiederhergestellt werden.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDiscard = false; discard() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Verwerfen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Weiter aufnehmen") }
            },
        )
    }
}

@Composable
private fun StatusPill(active: Boolean, paused: Boolean) {
    val pulse = rememberInfiniteTransition(label = "dot")
    val a by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    val live = active && !paused
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .scale(if (live) 0.8f + 0.2f * a else 1f)
                .clip(CircleShape)
                .background(
                    if (live) RecordRed.copy(alpha = a.coerceAtLeast(0.35f))
                    else Color.White.copy(alpha = 0.5f)
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                !active -> "Wird gestartet …"
                paused -> "Pausiert"
                else -> "Aufnahme läuft"
            },
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

/** Live-Wellenform: neueste Pegel rechts, ältere verblassen nach links. */
@Composable
private fun Waveform(levels: List<Float>, paused: Boolean, modifier: Modifier = Modifier) {
    val barColor = if (paused) Color.White.copy(alpha = 0.35f) else Color(0xFFB4B0FF)
    val accent = if (paused) Color.White.copy(alpha = 0.35f) else Color.White
    Canvas(modifier = modifier) {
        val barW = 4.dp.toPx()
        val gap = 3.dp.toPx()
        val step = barW + gap
        val count = (size.width / step).toInt().coerceAtLeast(1)
        val data = if (levels.size >= count) levels.takeLast(count)
        else List(count - levels.size) { 0f } + levels
        val midY = size.height / 2f
        val minH = 3.dp.toPx()
        data.forEachIndexed { i, raw ->
            val lvl = raw.coerceIn(0f, 1f).toDouble().pow(0.6).toFloat()
            val h = (lvl * size.height * 0.95f).coerceAtLeast(minH)
            val age = i.toFloat() / count            // 0 = alt, 1 = neu
            val alpha = 0.25f + 0.75f * age
            val color = if (i >= count - 3) accent else barColor
            drawRoundRect(
                color = color.copy(alpha = color.alpha * alpha),
                topLeft = Offset(i * step, midY - h / 2f),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

@Composable
private fun RoundControl(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .padding(top = 12.dp)
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (enabled) 0.12f else 0.05f))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = label,
                tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f))
    }
}

@Composable
private fun StopControl(enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .padding(6.dp)
                .clip(CircleShape)
                .background(if (enabled) RecordRed else RecordRed.copy(alpha = 0.4f))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.White)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Fertig", style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

@Composable
private fun PermissionOrErrorView(
    error: String?,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MicOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (error != null) "Aufnahme nicht möglich" else "Mikrofon-Zugriff benötigt",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            error ?: ("Um Besprechungen aufzunehmen, braucht Protokoll KI Zugriff auf das Mikrofon. " +
                "Die Aufnahme wird ausschließlich auf diesem Gerät gespeichert und verarbeitet."),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Erneut versuchen") }
        Spacer(Modifier.height(8.dp))
        if (error == null) {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("App-Einstellungen öffnen", color = Color.White)
            }
        }
        TextButton(onClick = onBack) { Text("Zurück", color = Color.White.copy(alpha = 0.8f)) }
    }
}
