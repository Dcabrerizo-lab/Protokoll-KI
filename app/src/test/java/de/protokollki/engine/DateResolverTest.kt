package de.protokollki.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DateResolverTest {
    private val monday = LocalDate.of(2026, 11, 23)
    private fun p(s: String) = DateResolver.parse(s, monday)

    @Test fun weekday() = assertEquals(LocalDate.of(2026, 11, 27), p("bis Freitag"))
    @Test fun nextWeekWeekday() = assertEquals(LocalDate.of(2026, 12, 2), p("nächste Woche Mittwoch"))
    @Test fun endOfMonthNextYear() = assertEquals(LocalDate.of(2027, 1, 31), p("Ende Januar"))
    @Test fun dayMonth() = assertEquals(LocalDate.of(2027, 1, 15), p("15. Januar"))
    @Test fun numeric() = assertEquals(LocalDate.of(2026, 12, 4), p("04.12."))
    @Test fun inTwoWeeks() = assertEquals(LocalDate.of(2026, 12, 7), p("in zwei Wochen"))
    @Test fun tomorrow() = assertEquals(LocalDate.of(2026, 11, 24), p("morgen"))
    @Test fun endOfWeek() = assertEquals(LocalDate.of(2026, 11, 27), p("Ende der Woche"))
    @Test fun unknownKept() = assertEquals("nächste Sitzung", DateResolver.resolve("nächste Sitzung", monday))
    @Test fun formatted() = assertEquals("Fr., 27.11.2026", DateResolver.resolve("Freitag", monday))
}
