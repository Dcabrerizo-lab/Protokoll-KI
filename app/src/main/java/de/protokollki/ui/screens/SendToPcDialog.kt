package de.protokollki.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.protokollki.data.Meeting
import de.protokollki.transfer.PcPackage
import de.protokollki.transfer.PcTransfer
import kotlinx.coroutines.launch

/**
 * „Am PC auswerten“: Aufnahme als .protokoll-Datei per Quick Share/Teilen schicken oder im Ordner
 * „Download“ speichern (für das USB-Kabel). Am PC übernimmt das Programm „Protokoll KI für Windows“.
 */
@Composable
fun SendToPcDialog(m: Meeting, onDismiss: () -> Unit, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PcPackage.MIME)
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = true
        scope.launch {
            try {
                PcTransfer.saveTo(context, m, uri)
                onDismiss()
                onResult("Gespeichert. Jetzt das Handy per USB-Kabel anschließen und die Datei in Protokoll KI am PC ziehen.")
            } catch (e: Exception) {
                onResult(e.message ?: "Speichern fehlgeschlagen.")
            } finally {
                working = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        icon = { Icon(Icons.Filled.Computer, contentDescription = null) },
        title = { Text("Am PC auswerten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Die Aufnahme geht mit Vorlage, Tagesordnung und Wörterbuch an das Programm " +
                        "„Protokoll KI für Windows“. Es wertet offline aus, meist deutlich schneller. " +
                        "Das fertige Protokoll kann danach zurück in die App.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Hint(Icons.Filled.Share, "Quick Share oder Teilen", "Ohne Kabel. Am PC erscheint die Datei im Download-Ordner, das Programm fragt dann „Übernehmen?“.")
                Hint(Icons.Filled.Usb, "USB-Kabel", "Datei in „Download“ speichern, Handy anschließen und die Datei ins PC-Programm ziehen.")
                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Datei wird vorbereitet …", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(
                    onClick = {
                        working = true
                        scope.launch {
                            try {
                                val f = PcTransfer.createPackage(context, m)
                                PcTransfer.share(context, f, m)
                                onDismiss()
                            } catch (e: Exception) {
                                onResult(e.message ?: "Die Datei konnte nicht erstellt werden.")
                            } finally {
                                working = false
                            }
                        }
                    },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Teilen (Quick Share …)")
                }
                OutlinedButton(
                    onClick = { saveLauncher.launch(PcTransfer.suggestedName(m)) },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Filled.SaveAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Auf dem Handy speichern (USB)")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Schließen") } },
    )
}

@Composable
private fun Hint(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
