package de.protokollki.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Wandelt deutsche Fristangaben ("bis Freitag", "Ende Januar", "15. Januar",
 * "in zwei Wochen") relativ zum Besprechungsdatum in ein festes Datum um.
 * Deterministisch, damit Fristen nicht vom Sprachmodell "verrechnet" werden.
 * Nicht erkennbare Angaben werden unverändert zurückgegeben.
 */
object DateResolver {
    private val out = DateTimeFormatter.ofPattern("EE, dd.MM.yyyy", Locale.GERMAN)

    private val weekdays = mapOf(
        "montag" to DayOfWeek.MONDAY, "dienstag" to DayOfWeek.TUESDAY,
        "mittwoch" to DayOfWeek.WEDNESDAY, "donnerstag" to DayOfWeek.THURSDAY,
        "freitag" to DayOfWeek.FRIDAY, "samstag" to DayOfWeek.SATURDAY,
        "sonnabend" to DayOfWeek.SATURDAY, "sonntag" to DayOfWeek.SUNDAY,
    )
    private val months = mapOf(
        "januar" to 1, "jänner" to 1, "jan" to 1, "februar" to 2, "feb" to 2, "märz" to 3,
        "maerz" to 3, "april" to 4, "apr" to 4, "mai" to 5, "juni" to 6, "juli" to 7,
        "august" to 8, "aug" to 8, "september" to 9, "sept" to 9, "sep" to 9, "oktober" to 10,
        "okt" to 10, "november" to 11, "nov" to 11, "dezember" to 12, "dez" to 12,
    )
    private val numbers = mapOf(
        "einem" to 1, "einer" to 1, "ein" to 1, "eine" to 1, "zwei" to 2, "drei" to 3,
        "vier" to 4, "fünf" to 5, "sechs" to 6, "sieben" to 7, "acht" to 8, "zehn" to 10,
        "vierzehn" to 14,
    )

    fun resolve(raw: String, meeting: LocalDate): String {
        val date = parse(raw, meeting) ?: return raw.trim().ifEmpty { "offen" }
        return out.format(date).replace("..", ".")
    }

    private fun num(s: String): Int? = s.toIntOrNull() ?: numbers[s]

    private fun yearFor(month: Int, day: Int, meeting: LocalDate): Int {
        val candidate = runCatching { LocalDate.of(meeting.year, month, day) }.getOrNull()
        return if (candidate != null && candidate.isBefore(meeting.minusDays(14))) meeting.year + 1
        else meeting.year
    }

    fun parse(raw: String, d: LocalDate): LocalDate? {
        val t = raw.lowercase(Locale.GERMAN).replace(Regex("[,;]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || t == "offen") return null

        // TT.MM.JJJJ / TT.MM.JJ / TT.MM.
        Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})?").find(t)?.let { m ->
            val day = m.groupValues[1].toInt(); val mon = m.groupValues[2].toInt()
            if (mon in 1..12 && day in 1..31) {
                val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
                    ?.let { if (it < 100) 2000 + it else it } ?: yearFor(mon, day, d)
                return runCatching { LocalDate.of(y, mon, day) }.getOrNull()
            }
        }
        // "15. Januar (2027)"
        Regex("(\\d{1,2})\\.? ?(${months.keys.joinToString("|")})\\b(?: (\\d{4}))?").find(t)?.let { m ->
            val day = m.groupValues[1].toInt(); val mon = months.getValue(m.groupValues[2])
            val y = m.groupValues[3].toIntOrNull() ?: yearFor(mon, day, d)
            return runCatching { LocalDate.of(y, mon, day) }.getOrNull()
        }
        // Ende/Anfang/Mitte <Monat>
        Regex("(ende|anfang|mitte|spätestens ende) (?:des )?(${months.keys.joinToString("|")})\\b").find(t)?.let { m ->
            val mon = months.getValue(m.groupValues[2])
            val first = LocalDate.of(yearFor(mon, 28, d), mon, 1)
            return when {
                m.groupValues[1].contains("ende") -> first.with(TemporalAdjusters.lastDayOfMonth())
                m.groupValues[1] == "mitte" -> first.withDayOfMonth(15)
                else -> first
            }
        }
        if (Regex("heute").containsMatchIn(t)) return d
        if (Regex("übermorgen").containsMatchIn(t)) return d.plusDays(2)
        if (Regex("\\bmorgen\\b").containsMatchIn(t)) return d.plusDays(1)
        if (Regex("ende (des|dieses) monats|monatsende|ende des laufenden monats").containsMatchIn(t))
            return d.with(TemporalAdjusters.lastDayOfMonth())
        if (Regex("ende (des )?nächsten monats").containsMatchIn(t))
            return d.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth())
        if (Regex("ende (der|dieser) woche|wochenende").containsMatchIn(t))
            return d.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
        if (Regex("ende (der )?nächste[nr]? woche").containsMatchIn(t))
            return d.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)).plusWeeks(1)
        if (Regex("ende des jahres|jahresende").containsMatchIn(t))
            return LocalDate.of(d.year, 12, 31)

        // in X Tagen/Wochen/Monaten
        Regex("in (\\w+) (tag|tagen|woche|wochen|monat|monaten)\\b").find(t)?.let { m ->
            val n = num(m.groupValues[1]) ?: return@let
            return when {
                m.groupValues[2].startsWith("tag") -> d.plusDays(n.toLong())
                m.groupValues[2].startsWith("woche") -> d.plusWeeks(n.toLong())
                else -> d.plusMonths(n.toLong())
            }
        }
        // Wochentage ("bis Freitag", "nächsten Dienstag", "nächste Woche Mittwoch")
        for ((name, dow) in weekdays) {
            if (Regex("\\b$name\\b").containsMatchIn(t)) {
                var date = d.with(TemporalAdjusters.next(dow))
                if (Regex("nächste woche|übernächste").containsMatchIn(t) &&
                    date.isBefore(d.with(TemporalAdjusters.next(DayOfWeek.MONDAY)))) {
                    date = date.plusWeeks(1)
                }
                return date
            }
        }
        if (Regex("^(bis )?(zur |in der )?nächste[nr]? woche$").containsMatchIn(t))
            return d.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        return null
    }
}
