package de.protokollki.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class VocabularyTest {

    private fun fix(text: String, vararg terms: String) = Vocabulary.correct(text, terms.toList())

    @Test fun `koelner phonetik referenzwerte`() {
        // Referenzbeispiele aus der Literatur
        assertEquals("65752682", Vocabulary.colognePhonetic("Müller-Lüdenscheidt"))
        assertEquals(Vocabulary.colognePhonetic("Meier"), Vocabulary.colognePhonetic("Mayer"))
        assertEquals(Vocabulary.colognePhonetic("Schmidt"), Vocabulary.colognePhonetic("Schmitt"))
    }

    @Test fun `korrigiert falsch geschriebene namen nach klang`() {
        assertEquals("Frau Schmitt übernimmt das.", fix("Frau Schmidt übernimmt das.", "Schmitt"))
        assertEquals("Herr Meyer ruft an.", fix("Herr Maier ruft an.", "Meyer"))
        assertEquals("Wir sprechen mit Frau Kowalczyk.", fix("Wir sprechen mit Frau Kowaltschik.", "Kowalczyk"))
    }

    @Test fun `fachbegriffe und schreibweise`() {
        assertEquals("Der Antrag nach SGB XI läuft.", fix("Der Antrag nach sgb xi läuft.", "SGB XI"))
        assertEquals("Die Pflegekasse zahlt.", fix("Die Pflegekase zahlt.", "Pflegekasse"))
    }

    @Test fun `mehrwortbegriffe werden zusammen ersetzt`() {
        assertEquals("Das Haus Sonnenschein meldet sich.",
            fix("Das Haus Sonnenschain meldet sich.", "Haus Sonnenschein"))
    }

    @Test fun `satzzeichen und genitiv bleiben erhalten`() {
        assertEquals("Schmitts Antrag, Schmitt!", fix("Schmidts Antrag, Schmidt!", "Schmitt"))
    }

    @Test fun `keine fehlkorrekturen bei normalen woertern`() {
        val text = "Wir machen das morgen, danke für den Termin und die Woche."
        assertEquals(text, fix(text, "Marchen", "Mogen", "Termine", "Wache"))
        // unähnliche Wörter bleiben
        assertEquals("Herr Becker kommt.", fix("Herr Becker kommt.", "Schneider"))
        // kurze Begriffe werden nicht unscharf ersetzt
        assertEquals("Das ist gut.", fix("Das ist gut.", "Gut"))
    }

    @Test fun `leeres woerterbuch aendert nichts`() {
        assertEquals("Hallo Welt", fix("Hallo Welt"))
        assertEquals("", fix("", "Schmitt"))
    }

    @Test fun levenshtein() {
        assertEquals(0, Vocabulary.levenshtein("abc", "abc"))
        assertEquals(1, Vocabulary.levenshtein("schmidt", "schmitt"))
        assertEquals(3, Vocabulary.levenshtein("", "abc"))
    }
}
