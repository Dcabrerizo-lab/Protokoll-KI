package de.protokollki.ui.screens

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.protokollki.data.AppSettings
import de.protokollki.data.ProtocolMode
import de.protokollki.engine.ModelCatalog
import de.protokollki.engine.ModelManager
import de.protokollki.service.DownloadService
import de.protokollki.ui.components.ModelDownloadPanel
import de.protokollki.ui.components.ModelSetCard
import de.protokollki.ui.components.OfflineBadge
import de.protokollki.ui.components.formatGb
import de.protokollki.ui.components.formatRam
import de.protokollki.ui.hasPermission

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    val ms by remember { ModelManager.state }.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var refresh by remember { mutableIntStateOf(0) }
    var confirmCleanup by remember { mutableStateOf(false) }

    val freeBytes = remember(ms.downloading, ms.installed, ms.set.id, refresh) {
        runCatching { ModelManager.freeBytes() }.getOrDefault(0L)
    }
    val missingBytes = remember(ms.downloading, ms.installed, ms.set.id, refresh) {
        runCatching { ModelManager.missingBytes(ms.set) }.getOrDefault(ms.set.downloadBytes)
    }
    val installedMap = remember(ms.downloading, ms.installed, ms.set.id, refresh) {
        ModelCatalog.sets.associate { it.id to runCatching { ModelManager.isInstalled(it) }.getOrDefault(false) }
    }
    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "–"
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> DownloadService.start(context) }
    val startDownload: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            DownloadService.start(context)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 32.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ------------------------------------------------ Protokoll
            item(key = "protocol") {
                SettingsGroup("Protokoll") {
                    Column(Modifier.padding(16.dp)) {
                        Text("Standard-Protokollart", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Gilt für neue Aufnahmen. Pro Besprechung jederzeit umschaltbar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        val modes = listOf(ProtocolMode.ERGEBNIS, ProtocolMode.WORT)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { i, mode ->
                                SegmentedButton(
                                    selected = settings.defaultMode == mode,
                                    onClick = { AppSettings.update { it.copy(defaultMode = mode) } },
                                    shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                                ) { Text(mode.label, maxLines = 1) }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Automatisch verarbeiten", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Nach dem Beenden der Aufnahme sofort Transkript und Protokoll erstellen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = settings.autoProcess,
                            onCheckedChange = { v -> AppSettings.update { it.copy(autoProcess = v) } },
                        )
                    }
                }
            }

            // ------------------------------------------------ Modelle
            item(key = "models") {
                SettingsGroup("KI-Modellpaket", plain = true) {
                    Text(
                        "Größere Pakete liefern genauere Transkripte und bessere Protokolle, " +
                            "brauchen aber mehr Arbeitsspeicher und Zeit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Spacer(Modifier.height(10.dp))
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
                    if (!ms.installed || ms.downloading || ms.error != null) {
                        Spacer(Modifier.height(14.dp))
                        ModelDownloadPanel(
                            state = ms,
                            freeBytes = freeBytes,
                            missingBytes = missingBytes,
                            onStart = startDownload,
                            onCancel = { DownloadService.cancel(context) },
                        )
                    }
                }
            }

            // ------------------------------------------------ Speicher & Gerät
            item(key = "device") {
                SettingsGroup("Speicher & Gerät") {
                    InfoRow(Icons.Filled.Memory, "Arbeitsspeicher", formatRam(ms.ramGb))
                    InfoRow(Icons.Filled.Smartphone, "Empfohlenes Paket", ms.recommended.displayName)
                    InfoRow(Icons.Filled.SdStorage, "Freier Speicher", formatGb(freeBytes))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(Modifier.padding(16.dp)) {
                        OutlinedButton(
                            onClick = { confirmCleanup = true },
                            enabled = !ms.downloading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.CleaningServices, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Nicht benötigte Modelle löschen")
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Entfernt Modelle anderer Pakete. Das gewählte Paket „${ms.set.label}“ bleibt erhalten.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ------------------------------------------------ Datenschutz
            item(key = "privacy") {
                SettingsGroup("Datenschutz") {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Ihre Daten bleiben bei Ihnen",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            OfflineBadge()
                        }
                        Spacer(Modifier.height(12.dp))
                        PrivacyPoint(
                            Icons.Filled.Lock,
                            "Aufnahmen, Transkripte und Protokolle werden ausschließlich im privaten Speicher dieser App abgelegt.",
                        )
                        PrivacyPoint(
                            Icons.Filled.AutoAwesome,
                            "Spracherkennung, Sprechererkennung und Protokoll-KI laufen vollständig auf diesem Gerät.",
                        )
                        PrivacyPoint(
                            Icons.Filled.WifiOff,
                            "Internet wird nur einmal für den Download der KI-Modelle genutzt. Danach funktioniert die App auch im Flugmodus.",
                        )
                        PrivacyPoint(
                            Icons.Filled.Info,
                            "Kein Konto, keine Cloud, keine Analyse- oder Werbedienste. Kein Backup in die Cloud.",
                        )
                    }
                }
            }

            // ------------------------------------------------ Über
            item(key = "about") {
                SettingsGroup("Über") {
                    InfoRow(Icons.Filled.Info, "Protokoll KI", "Version $version")
                }
            }
        }
    }

    if (confirmCleanup) {
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            icon = { Icon(Icons.Filled.CleaningServices, contentDescription = null) },
            title = { Text("Nicht benötigte Modelle löschen?") },
            text = {
                Text(
                    "Modelle, die nicht zum gewählten Paket „${ms.set.label}“ gehören, werden gelöscht. " +
                        "Ein späterer Wechsel erfordert einen erneuten Download."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCleanup = false
                    val before = runCatching { ModelManager.freeBytes() }.getOrDefault(0L)
                    runCatching { ModelManager.deleteUnused() }
                    val after = runCatching { ModelManager.freeBytes() }.getOrDefault(0L)
                    refresh++
                    val freed = (after - before).coerceAtLeast(0L)
                    Toast.makeText(context, "${formatGb(freed)} freigegeben", Toast.LENGTH_SHORT).show()
                }) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCleanup = false }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    plain: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        )
        if (plain) {
            Column(content = content)
        } else {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(content = content)
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PrivacyPoint(icon: ImageVector, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(
            icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
