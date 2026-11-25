package de.protokollki.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.protokollki.data.AppSettings
import de.protokollki.engine.ModelCatalog
import de.protokollki.engine.ModelManager
import de.protokollki.service.DownloadService
import de.protokollki.ui.components.IllustrationIcon
import de.protokollki.ui.components.ModelDownloadPanel
import de.protokollki.ui.components.ModelSetCard
import de.protokollki.ui.components.formatGb
import de.protokollki.ui.components.formatRam
import de.protokollki.ui.hasPermission

private const val STEPS = 3

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val cs = MaterialTheme.colorScheme

    BackHandler(enabled = step > 0) { step-- }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(cs.primaryContainer.copy(alpha = 0.55f), cs.background, cs.background)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 3 } + fadeOut())
                    } else {
                        (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { it / 3 } + fadeOut())
                    }
                },
                modifier = Modifier.weight(1f),
                label = "onboarding",
            ) { s ->
                when (s) {
                    0 -> WelcomeStep()
                    1 -> FeaturesStep()
                    else -> ModelStep(onFinished = onFinished)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageDots(current = step, count = STEPS)
                Spacer(Modifier.weight(1f))
                if (step < STEPS - 1) {
                    Button(
                        onClick = { step++ },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(52.dp),
                    ) {
                        Text(if (step == 0) "Los geht's" else "Weiter")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageDots(current: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == current) 24.dp else 8.dp, label = "dotW")
            val c by animateColorAsState(
                if (i == current) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                label = "dotC",
            )
            Box(
                Modifier
                    .height(8.dp)
                    .width(w)
                    .clip(CircleShape)
                    .background(c)
            )
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(32.dp))
        IllustrationIcon(icon = Icons.Filled.GraphicEq, size = 168.dp)
        Spacer(Modifier.height(32.dp))
        Text(
            "Protokoll KI",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Besprechung aufnehmen.\nProtokoll erhalten.",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Transkript mit Sprechern, Ergebnis- oder Wortprotokoll und Aufgabenliste – " +
                "automatisch erstellt, direkt auf Ihrem Smartphone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Lock, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "100 % offline",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Ihre Gespräche verlassen nie das Gerät.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun FeaturesStep() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(32.dp))
        Text("So funktioniert's", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Vier Schritte – alles läuft lokal auf Ihrem Gerät.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        FeatureRow(Icons.Filled.Mic, "Aufnehmen oder importieren",
            "Besprechung live mitschneiden oder eine vorhandene Audiodatei öffnen.")
        FeatureRow(Icons.Filled.RecordVoiceOver, "Sprecher erkennen",
            "Die KI trennt die Stimmen und ordnet jeden Redebeitrag einer Person zu.")
        FeatureRow(Icons.Filled.Description, "Protokoll erstellen",
            "Ergebnisprotokoll mit Themen und Beschlüssen – oder vollständiges Wortprotokoll.")
        FeatureRow(Icons.Filled.TaskAlt, "Aufgaben ableiten",
            "Wer macht was bis wann – als abhakbare Liste.")
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.WifiOff, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Kein Konto, keine Cloud. Internet wird nur einmal für den Download der KI-Modelle benötigt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.RecordVoiceOver, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Bitte informieren Sie vor jeder Aufnahme alle Anwesenden und holen Sie ihr Einverständnis ein. " +
                        "KI-Protokolle können Fehler enthalten: vor der Weitergabe kurz prüfen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelStep(onFinished: () -> Unit) {
    val context = LocalContext.current
    val ms by remember { ModelManager.state }.collectAsStateWithLifecycle()
    val freeBytes = remember(ms.downloading, ms.installed, ms.set.id) {
        runCatching { ModelManager.freeBytes() }.getOrDefault(0L)
    }
    val missingBytes = remember(ms.downloading, ms.installed, ms.set.id) {
        runCatching { ModelManager.missingBytes(ms.set) }.getOrDefault(ms.set.downloadBytes)
    }
    val installedMap = remember(ms.downloading, ms.installed, ms.set.id) {
        ModelCatalog.sets.associate { it.id to runCatching { ModelManager.isInstalled(it) }.getOrDefault(false) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> DownloadService.start(context) }

    val startDownload: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 &&
            !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            DownloadService.start(context)
        }
    }

    val finish: () -> Unit = {
        AppSettings.update { it.copy(onboardingDone = true) }
        onFinished()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("KI-Modelle einrichten", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Damit alles offline funktioniert, werden die KI-Modelle einmalig auf Ihr Gerät geladen. " +
                "Wir haben das passende Paket für Ihr Gerät vorausgewählt.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DeviceStat(
                icon = Icons.Filled.Memory,
                label = "Arbeitsspeicher",
                value = formatRam(ms.ramGb),
                modifier = Modifier.weight(1f),
            )
            DeviceStat(
                icon = Icons.Filled.SdStorage,
                label = "Freier Speicher",
                value = formatGb(freeBytes),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(20.dp))
        // Ein Klick genügt: das empfohlene Paket ist vorausgewählt, eigene Auswahl nur bei Bedarf
        var showAll by rememberSaveable { mutableStateOf(ms.set.id != ms.recommended.id) }
        Text(
            if (ms.set.id == ms.recommended.id) "Automatisch gewählt: ${ms.recommended.label} " +
                "(schnellste Einstellung mit bester Qualität für dieses Gerät)"
            else "Ausgewählt: ${ms.set.label} (empfohlen wäre ${ms.recommended.label})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        ModelDownloadPanel(
            state = ms,
            freeBytes = freeBytes,
            missingBytes = missingBytes,
            onStart = startDownload,
            onCancel = { DownloadService.cancel(context) },
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showAll) "Paketauswahl ausblenden" else "Anderes Paket wählen (optional)")
        }
        if (showAll) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModelCatalog.sets.forEach { set ->
                    ModelSetCard(
                        set = set,
                        selected = set.id == ms.set.id,
                        recommended = set.id == ms.recommended.id,
                        installed = installedMap[set.id] == true,
                        enabled = !ms.downloading,
                        onClick = { if (!ms.downloading) ModelManager.selectSet(set.id) },
                    )
                }
            }
        }
        if (ms.installed && !ms.downloading) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = finish,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Fertig – zur App")
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        } else {
            TextButton(
                onClick = onFinished,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Später einrichten")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DeviceStat(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleMedium)
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
