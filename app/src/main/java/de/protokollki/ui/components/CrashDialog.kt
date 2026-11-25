package de.protokollki.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Nach einem Absturz beim nächsten Start: Bericht freiwillig per E-Mail senden. */
@Composable
fun CrashDialog(onSend: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.BugReport, contentDescription = null) },
        title = { Text("Protokoll KI wurde unerwartet beendet") },
        text = {
            Text(
                "Ihre Aufnahmen und Protokolle sind sicher. Möchten Sie uns einen technischen Bericht schicken? " +
                    "Er enthält nur Gerät, Version und Fehlerstelle, keine Inhalte. Sie sehen ihn vor dem Senden in Ihrer E-Mail-App."
            )
        },
        confirmButton = { TextButton(onClick = onSend) { Text("Bericht senden") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Nicht senden") } },
    )
}
