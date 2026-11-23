package de.protokollki.ui.components

import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.protokollki.data.Meeting
import de.protokollki.data.MeetingRepository
import de.protokollki.data.ProtocolTemplate
import de.protokollki.export.MINUTE_TAKER_ROLE
import de.protokollki.export.isFormal

// ================================================================ Vorlagen: Logik

/** Kleine UI-Merkwerte (z. B. zuletzt eingetragene Protokollführung), getrennt von den Einstellungen. */
object UiPrefs {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun lastMinuteTaker(context: Context): String =
        runCatching { prefs(context).getString("lastMinuteTaker", "") ?: "" }.getOrDefault("")

    fun rememberMinuteTaker(context: Context, name: String) {
        val v = name.trim()
        if (v.isNotEmpty()) runCatching { prefs(context).edit().putString("lastMinuteTaker", v).apply() }
    }
}

/** Ob die Vorlage eine Protokollführung vorsieht (dann wird der zuletzt benutzte Name vorbelegt). */
fun ProtocolTemplate.hasMinuteTaker(): Boolean = MINUTE_TAKER_ROLE in signatures

/**
 * Setzt die Vorlage einer Besprechung. Bei Vorlagen mit Protokollführung wird ein leeres Feld
 * mit dem zuletzt benutzten Namen vorbelegt – spart Tipparbeit.
 */
fun applyTemplate(context: Context, meetingId: String, template: ProtocolTemplate): Meeting? {
    val last = if (template.hasMinuteTaker()) UiPrefs.lastMinuteTaker(context) else ""
    return MeetingRepository.update(meetingId) { m ->
        m.copy(
            template = template,
            minuteTaker = if (m.minuteTaker.isBlank() && last.isNotBlank()) last else m.minuteTaker,
        )
    }
}

fun templateIcon(t: ProtocolTemplate): ImageVector = when (t) {
    ProtocolTemplate.ALLGEMEIN -> Icons.Filled.Description
    ProtocolTemplate.DIENSTBESPRECHUNG -> Icons.Filled.Groups
    ProtocolTemplate.FALLBESPRECHUNG -> Icons.Filled.FolderShared
    ProtocolTemplate.UEBERGABE -> Icons.Filled.SwapHoriz
    ProtocolTemplate.VEREINSSITZUNG -> Icons.Filled.Gavel
}

// ================================================================ Vorlagen: Oberfläche

/** Auswahl der Protokollvorlage als Bottom Sheet: Symbol, Name und kurze Beschreibung je Vorlage. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateSheet(
    selected: ProtocolTemplate,
    onSelect: (ProtocolTemplate) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Vorlage wählen",
    subtitle: String = "Bestimmt Gliederung und Formulierungen des Protokolls.",
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProtocolTemplate.entries.forEach { t ->
                    TemplateOption(template = t, selected = t == selected, onClick = { onSelect(t) })
                }
            }
        }
    }
}

@Composable
private fun TemplateOption(template: ProtocolTemplate, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) cs.primary else cs.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    templateIcon(template), contentDescription = null,
                    tint = if (selected) cs.onPrimary else cs.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    template.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) cs.onPrimaryContainer else cs.onSurface,
                )
                Text(
                    template.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant,
                )
                if (template.isFormal) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "„${template.documentTitle}“ · mit Unterschriftsfeld",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) cs.onPrimaryContainer else cs.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.CheckCircle, contentDescription = "Ausgewählt", tint = cs.primary)
            }
        }
    }
}

/** Kleines Etikett mit der Vorlage (z. B. unter dem Titel einer Besprechung); antippen = ändern. */
@Composable
fun TemplateChip(template: ProtocolTemplate, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = if (onClick != null) 4.dp else 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                templateIcon(template), contentDescription = null,
                tint = cs.onSecondaryContainer, modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                template.label,
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSecondaryContainer,
                maxLines = 1,
            )
            if (onClick != null) {
                Icon(
                    Icons.Filled.ArrowDropDown, contentDescription = "Vorlage ändern",
                    tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = RoundedCornerShape(50), color = cs.secondaryContainer, modifier = modifier) {
            content()
        }
    } else {
        Surface(shape = RoundedCornerShape(50), color = cs.secondaryContainer, modifier = modifier) { content() }
    }
}
