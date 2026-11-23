package de.protokollki.ui.screens

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.protokollki.audio.Recorder
import de.protokollki.data.AppSettings
import de.protokollki.data.MeetingRepository
import de.protokollki.data.MeetingStatus
import de.protokollki.privacy.AppLock
import de.protokollki.privacy.PrivacyReport
import de.protokollki.privacy.Retention
import de.protokollki.service.ProcessingService
import de.protokollki.tts.ReadAloud
import de.protokollki.ui.components.UiScope
import de.protokollki.ui.components.findActivity
import de.protokollki.ui.components.prepareShareUri
import de.protokollki.ui.components.startShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DAY_MS = 24L * 60 * 60 * 1000
private val RetentionOptions = listOf(0 to "Nie", 7 to "7 Tage", 30 to "30 Tage", 90 to "90 Tage")

private fun toastMain(context: Context, text: String) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
    }
}

/** Einstellungen „Datenschutz & Sicherheit“. */
@Composable
fun PrivacySecurityGroup() {
    val context = LocalContext.current
    val settings by AppSettings.state.collectAsStateWithLifecycle()
    val meetings by MeetingRepository.meetings.collectAsStateWithLifecycle()
    val lockAvailable = remember { runCatching { AppLock.isAvailable(context) }.getOrDefault(false) }

    var pendingRetention by remember { mutableStateOf<Int?>(null) }
    var creatingReport by remember { mutableStateOf(false) }
    var deleteStep by remember { mutableStateOf(0) }   // 0 = kein Dialog, 1 = erste, 2 = zweite Bestätigung
    var deleting by remember { mutableStateOf(false) }

    fun applyRetention(days: Int) {
        AppSettings.update { it.copy(audioRetentionDays = days) }
        val app = context.applicationContext
        UiScope.launch {
            withContext(Dispatchers.IO) { runCatching { Retention.apply(app) } }
        }
    }

    fun affectedBy(days: Int): Int {
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - days * DAY_MS
        return meetings.count {
            !it.audioDeleted && it.status != MeetingStatus.RECORDING && !it.isBusy && it.createdAt < cutoff
        }
    }

    SettingsGroup("Datenschutz & Sicherheit") {
        // ------------------------------------------------ App-Sperre
        SwitchRow(
            icon = Icons.Filled.Fingerprint,
            title = "App-Sperre",
            subtitle = if (lockAvailable || settings.appLock) {
                "Beim Öffnen per Fingerabdruck, Gesicht oder Geräte-PIN entsperren (nach 1 Minute im Hintergrund)."
            } else {
                "Richten Sie zuerst eine Displaysperre (PIN, Muster oder Fingerabdruck) auf dem Gerät ein."
            },
            checked = settings.appLock,
            enabled = lockAvailable || settings.appLock,
            onChange = { on ->
                if (!on) {
                    AppSettings.update { it.copy(appLock = false) }
                } else {
                    val activity = context.findActivity()
                    if (activity == null) {
                        toastMain(context, "App-Sperre konnte nicht aktiviert werden.")
                    } else {
                        runCatching {
                            AppLock.authenticate(
                                activity,
                                {
                                    AppSettings.update { it.copy(appLock = true) }
                                    toastMain(context, "App-Sperre ist aktiv.")
                                },
                                { msg -> toastMain(context, msg.ifBlank { "Bestätigung abgebrochen." }) },
                            )
                        }.onFailure { e ->
                            toastMain(context, e.message ?: "App-Sperre konnte nicht aktiviert werden.")
                        }
                    }
                }
            },
        )
        GroupDivider()
        // ------------------------------------------------ Screenshots
        SwitchRow(
            icon = Icons.Filled.VisibilityOff,
            title = "Screenshots blockieren",
            subtitle = "Verhindert Bildschirmfotos und verbirgt Inhalte in der Übersicht der zuletzt genutzten Apps.",
            checked = settings.blockScreenshots,
            onChange = { v -> AppSettings.update { it.copy(blockScreenshots = v) } },
        )
        GroupDivider()
        // ------------------------------------------------ Löschfrist
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowIcon(Icons.Filled.DeleteSweep)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Audio automatisch löschen nach", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Transkript und Protokoll bleiben erhalten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RetentionOptions.forEach { (days, label) ->
                    FilterChip(
                        selected = settings.audioRetentionDays == days,
                        onClick = {
                            if (settings.audioRetentionDays != days) {
                                if (affectedBy(days) > 0) pendingRetention = days else applyRetention(days)
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
        GroupDivider()
        // ------------------------------------------------ Immer schwärzen
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowIcon(Icons.Filled.VisibilityOff)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Immer schwärzen", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Namen oder Begriffe, die in anonymisierten Exporten immer ersetzt werden – " +
                            "zusätzlich zu den automatisch erkannten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            TermChipsEditor(
                terms = settings.customTerms,
                onChange = { list -> AppSettings.update { it.copy(customTerms = list) } },
                placeholder = "z. B. Name eines Klienten",
            )
        }
        GroupDivider()
        // ------------------------------------------------ Nachweis & Löschen
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !creatingReport,
                onClick = {
                    creatingReport = true
                    val app = context.applicationContext
                    UiScope.launch {
                        try {
                            val uri = withContext(Dispatchers.IO) {
                                prepareShareUri(app, PrivacyReport.create(app))
                            }
                            startShare(context, uri, "application/pdf", "Datenschutz-Nachweis")
                        } catch (e: Exception) {
                            toastMain(context, "Nachweis konnte nicht erstellt werden: ${e.message ?: "unbekannter Fehler"}")
                        } finally {
                            creatingReport = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (creatingReport) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.VerifiedUser, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (creatingReport) "Nachweis wird erstellt …" else "Datenschutz-Nachweis erstellen")
            }
            Text(
                "PDF für Datenschutzbeauftragte: Verarbeitung nur auf dem Gerät, Netzwerknutzung, Speicherort, Löschfrist.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { deleteStep = 1 },
                enabled = !deleting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                if (deleting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.DeleteForever, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text("Alle Daten löschen")
            }
        }
    }

    // ---------------------------------------------------------------- Dialoge

    pendingRetention?.let { days ->
        val n = affectedBy(days)
        AlertDialog(
            onDismissRequest = { pendingRetention = null },
            icon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
            title = { Text("Löschfrist $days Tage") },
            text = {
                Text(
                    (if (n == 1) "Die Aufnahme von 1 Besprechung ist älter als $days Tage und wird"
                    else "Die Aufnahmen von $n Besprechungen sind älter als $days Tage und werden") +
                        " sofort gelöscht. Transkripte und Protokolle bleiben erhalten."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { pendingRetention = null; applyRetention(days) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Übernehmen") }
            },
            dismissButton = { TextButton(onClick = { pendingRetention = null }) { Text("Abbrechen") } },
        )
    }

    if (deleteStep == 1) {
        val recording = Recorder.state.value.active
        AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null) },
            title = { Text("Alle Daten löschen?") },
            text = {
                Text(
                    if (recording) "Gerade läuft eine Aufnahme. Beenden Sie diese zuerst."
                    else "Alle ${meetings.size} Besprechungen mit Aufnahmen, Transkripten und Protokollen sowie alle Stimmprofile werden " +
                        "von diesem Gerät gelöscht. KI-Modelle und Einstellungen bleiben erhalten."
                )
            },
            confirmButton = {
                if (!recording) {
                    TextButton(
                        onClick = { deleteStep = 2 },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Weiter") }
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteStep = 0 }) { Text(if (recording) "OK" else "Abbrechen") }
            },
        )
    }

    if (deleteStep == 2) {
        AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Wirklich endgültig löschen?") },
            text = { Text("Das kann nicht rückgängig gemacht werden.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteStep = 0
                        deleting = true
                        val app = context.applicationContext
                        runCatching { ReadAloud.stop() }
                        runCatching { ProcessingService.cancel(app) }
                        UiScope.launch {
                            val ok = withContext(Dispatchers.IO) { runCatching { Retention.deleteAll(app) }.isSuccess }
                            deleting = false
                            toastMain(app, if (ok) "Alle Besprechungen wurden gelöscht." else "Löschen fehlgeschlagen.")
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Endgültig löschen") }
            },
            dismissButton = { TextButton(onClick = { deleteStep = 0 }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun RowIcon(icon: ImageVector) {
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
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
