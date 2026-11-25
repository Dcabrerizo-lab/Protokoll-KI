package de.protokollki.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Rechtstexte aus assets/legal (gleiche Dateien wie im PC-Programm). */
enum class LegalDoc(val id: String, val title: String, val file: String) {
    PRIVACY("datenschutz", "Datenschutzerklärung", "legal/datenschutz.md"),
    LICENSES("lizenzen", "Open-Source-Lizenzen", "legal/lizenzen.md"),
    IMPRINT("impressum", "Impressum", "legal/impressum.md");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: PRIVACY
    }
}

/** Liest einen Rechtstext; Platzhalter „[BITTE AUSFÜLLEN …]“ werden in der App nicht angezeigt. */
fun readLegal(context: Context, doc: LegalDoc): String =
    runCatching { context.assets.open(doc.file).bufferedReader().use { it.readText() } }
        .getOrDefault("")
        .lines()
        .filterNot { it.contains("[BITTE AUSFÜLLEN") }
        .joinToString("\n")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(doc: LegalDoc, onBack: () -> Unit) {
    val context = LocalContext.current
    val text = remember(doc) { readLegal(context, doc) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { pad ->
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                MarkdownLines(text)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** Sehr einfache Darstellung: Überschriften (#, ##), Aufzählungen (-), Absätze. */
@Composable
private fun MarkdownLines(text: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    text.lines().forEachIndexed { i, raw ->
        val line = raw.trimEnd()
        when {
            line.startsWith("# ") -> if (i > 0) Text(line.drop(2), style = MaterialTheme.typography.headlineSmall)
            line.startsWith("## ") -> {
                Spacer(Modifier.height(16.dp))
                Text(line.drop(3), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
            }
            line.startsWith("- ") -> Row(Modifier.padding(vertical = 2.dp)) {
                Text("•", style = MaterialTheme.typography.bodyMedium, color = muted)
                Spacer(Modifier.width(8.dp))
                Text(line.drop(2), style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            line.isBlank() -> Spacer(Modifier.height(6.dp))
            else -> Text(line, style = MaterialTheme.typography.bodyMedium, color = muted)
        }
    }
}
