package de.protokollki.ui.components

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

val GermanLocale: Locale = Locale.GERMANY

/** 1:02:03 bzw. 12:34 */
fun formatDuration(sec: Float): String {
    val total = sec.coerceAtLeast(0f).toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(GermanLocale, "%d:%02d:%02d", h, m, s)
    else String.format(GermanLocale, "%d:%02d", m, s)
}

/** Immer HH:MM:SS (Aufnahme-Timer). */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(
        GermanLocale, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60
    )
}

/** Lesbare Dauer: „45 Sek.“, „12 Min.“, „1 Std. 5 Min.“ */
fun formatDurationWords(sec: Float): String {
    val total = sec.coerceAtLeast(0f).toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        total < 60 -> "$total Sek."
        h == 0L -> "$m Min."
        m == 0L -> "$h Std."
        else -> "$h Std. $m Min."
    }
}

/** „Mo., 23. November 2026“ */
fun formatDate(millis: Long): String =
    SimpleDateFormat("EEE, d. MMMM yyyy", GermanLocale).format(Date(millis))

/** „23.11.2026“ */
fun formatDateShort(millis: Long): String =
    SimpleDateFormat("dd.MM.yyyy", GermanLocale).format(Date(millis))

/** „14:05“ */
fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm", GermanLocale).format(Date(millis))

private fun dayStart(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Gruppenüberschrift: „Heute“, „Gestern“ oder „Montag, 21. November“. */
fun formatDayHeader(millis: Long, now: Long = System.currentTimeMillis()): String {
    val today = dayStart(now)
    val day = dayStart(millis)
    val yesterday = Calendar.getInstance().apply {
        timeInMillis = today; add(Calendar.DAY_OF_YEAR, -1)
    }.timeInMillis
    val sameYear = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR) ==
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)
    return when (day) {
        today -> "Heute"
        yesterday -> "Gestern"
        else -> SimpleDateFormat(
            if (sameYear) "EEEE, d. MMMM" else "EEEE, d. MMMM yyyy", GermanLocale
        ).format(Date(millis))
    }
}

/** Schlüssel für die Tagesgruppierung. */
fun dayKey(millis: Long): Long = dayStart(millis)

/** „2,4 GB“ */
fun formatGb(bytes: Long): String = String.format(GermanLocale, "%.1f GB", bytes / 1e9)

/** „7,3 GB“ */
fun formatRam(gb: Double): String = String.format(GermanLocale, "%.1f GB", gb)

/** Initialen für Avatare: „Anna Schmidt“ -> „AS“, „Person 3“ -> „P3“. */
fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase(GermanLocale)
        else -> {
            val second = parts[1]
            val b = if (second.all { it.isDigit() }) second.take(2) else second.take(1)
            (parts[0].take(1) + b).uppercase(GermanLocale)
        }
    }
}
