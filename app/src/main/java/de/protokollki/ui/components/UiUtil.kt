package de.protokollki.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.Calendar

/** Sucht die Activity hinter einem (ggf. umhüllten) Context. */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Langlebiger Scope für kurze Arbeiten, die das Verlassen eines Bildschirms überleben sollen
 * (z. B. Stimmprofil speichern, Datenschutz-Nachweis erstellen).
 */
val UiScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

// ---------------------------------------------------------------- Fälligkeit

enum class DueState { NONE, OVERDUE, TODAY, SOON, LATER }

private val DueDateRegex = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})""")

/** Liest ein Datum „dd.MM.yyyy“ (auch „d.M.yyyy“) aus einem Fälligkeitstext; Tagesbeginn in Millis. */
fun parseDueDate(due: String): Long? {
    val match = DueDateRegex.find(due) ?: return null
    val d = match.groupValues[1].toIntOrNull() ?: return null
    val mo = match.groupValues[2].toIntOrNull() ?: return null
    val y = match.groupValues[3].toIntOrNull() ?: return null
    if (d !in 1..31 || mo !in 1..12 || y < 2000) return null
    return runCatching {
        Calendar.getInstance().apply {
            isLenient = false
            clear()
            set(y, mo - 1, d, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }.getOrNull()
}

/** Einordnung eines Fälligkeitstextes relativ zu heute. */
fun dueState(due: String, now: Long = System.currentTimeMillis()): DueState {
    val date = parseDueDate(due) ?: return DueState.NONE
    val today = dayKey(now)
    val inTwoDays = Calendar.getInstance().apply {
        timeInMillis = today; add(Calendar.DAY_OF_YEAR, 2)
    }.timeInMillis
    return when {
        date < today -> DueState.OVERDUE
        date == today -> DueState.TODAY
        date <= inTwoDays -> DueState.SOON
        else -> DueState.LATER
    }
}

/** „offen“, leer o. ä. = kein Wert. */
fun isOpenValue(v: String) = v.isBlank() || v.trim().equals("offen", ignoreCase = true)

// ---------------------------------------------------------------- Teilen

/**
 * Bereitet eine Datei zum Teilen vor (kopiert sie bei Bedarf nach cacheDir/exports, dem
 * FileProvider-Pfad). Blockierend – nicht auf dem Main-Thread aufrufen.
 */
fun prepareShareUri(context: Context, file: File): Uri {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val target = if (file.parentFile?.canonicalPath == dir.canonicalPath) file
    else File(dir, file.name).also { file.copyTo(it, overwrite = true) }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
}

/** Öffnet den System-Dialog „Teilen“ für eine vorbereitete Datei. */
fun startShare(context: Context, uri: Uri, mime: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        clipData = ClipData.newRawUri(title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, title)
    if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
