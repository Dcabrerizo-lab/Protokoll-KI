package de.protokollki

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.protokollki.data.AppSettings
import de.protokollki.data.MeetingRepository
import de.protokollki.data.ProtocolTemplate
import de.protokollki.engine.MeetingSearch
import de.protokollki.engine.ModelManager
import de.protokollki.testutil.Fixtures
import de.protokollki.testutil.Fixtures.UI_AUDIO_TITLE
import de.protokollki.testutil.Fixtures.UI_FORMAL_TITLE
import de.protokollki.testutil.Fixtures.UI_IMPORTED_FILE
import de.protokollki.testutil.Fixtures.UI_IMPORTED_TITLE
import de.protokollki.testutil.Fixtures.UI_OPEN_TASKS
import de.protokollki.testutil.Fixtures.UI_TASK_BECKER
import de.protokollki.testutil.TestEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Oberflächentests (Compose) ohne KI-Modelle: Startseite, Suche/Fragen, Besprechung mit Reitern,
 * Quellenbelegen und Vorlagen, Aufgabenübersicht, Einstellungen, importierte Protokolle und
 * Neuaufbau der Activity (Drehen). Laufzeit ≈ 1–3 min.
 *
 * Vor dem Start der Activity werden drei fertige Besprechungen angelegt. Damit die App ohne
 * Modelle die Startseite (statt der Einrichtung) zeigt, wird ein Modellpaket ohne Dateien gewählt
 * und nur dessen Anzeige-Flag „installiert“ gesetzt – so lädt garantiert kein Sprachmodell.
 */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {

    private var originalModelSet: String? = null
    private lateinit var seed: Fixtures.UiSeed

    /** Läuft VOR dem Start der Activity (äußere Regel der Kette). */
    private val appState = object : ExternalResource() {
        override fun before() {
            TestEnv.awaitNoBusyMeeting()
            originalModelSet = AppSettings.state.value.modelSetId
            TestEnv.resetAppState()
            ModelManager.selectSet(TestEnv.notInstalledSet().id)
            TestEnv.forceModelsInstalledFlag(true)
            seed = Fixtures.seedUiMeetings()
        }

        override fun after() {
            ModelManager.selectSet(originalModelSet)   // berechnet „installiert“ wieder aus den Dateien
            TestEnv.resetAppState()
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(appState).around(composeRule)

    // ================================================================ Hilfen

    private fun tab(label: String): SemanticsMatcher =
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun exists(matcher: SemanticsMatcher, unmerged: Boolean = false): Boolean =
        runCatching {
            composeRule.onAllNodes(matcher, unmerged).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)

    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 10_000, unmerged: Boolean = false) {
        composeRule.waitUntil(timeoutMs) { exists(matcher, unmerged) }
    }

    /** Scrollt die (einzige) Liste des Bildschirms, bis [matcher] sichtbar ist. */
    private fun scrollTo(matcher: SemanticsMatcher) {
        composeRule.waitForIdle()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
    }

    private fun scrollToTop() {
        composeRule.waitForIdle()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
    }

    /** Öffnet eine Besprechung über ihre Karte auf der Startseite. */
    private fun openMeeting(title: String) {
        waitFor(hasScrollToIndexAction())
        scrollTo(hasText(title))
        waitFor(hasText(title))
        composeRule.onNode(hasText(title) and hasClickAction()).performClick()
        waitFor(tab("Aufgaben"))
    }

    private fun secureFlagSet(): Boolean = composeRule.runOnUiThread {
        composeRule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    // ================================================================ Startseite + Fragen

    @Test
    fun home_listsMeetings_searchOpensAsk_hitOpensMeetingAtTranscript() {
        waitFor(hasText(UI_AUDIO_TITLE))
        composeRule.onNodeWithText(UI_AUDIO_TITLE).assertIsDisplayed()
        for (title in listOf(UI_FORMAL_TITLE, UI_IMPORTED_TITLE)) {
            scrollTo(hasText(title))
            composeRule.onNodeWithText(title).assertIsDisplayed()
        }
        scrollToTop()

        composeRule.onNodeWithText("Besprechungen durchsuchen oder fragen …").performClick()
        waitFor(hasText("Fragen an alle Besprechungen"))

        val query = "Fahrer"
        val expected = MeetingSearch.search(MeetingRepository.meetings.value, query)
        assertTrue("Testdaten: keine Treffer für $query", expected.isNotEmpty())
        assertTrue("Testdaten: Treffer außerhalb der Audio-Besprechung",
            expected.all { it.meetingId == seed.audio.id && it.segment >= 0 })

        composeRule.onNode(hasSetTextAction()).performTextInput(query)
        waitFor(hasText("Sofort-Treffer · ${expected.size}"))
        composeRule.onAllNodes(hasText(UI_AUDIO_TITLE) and hasClickAction())[0].performClick()

        waitFor(tab("Transkript") and isSelected())
        composeRule.onNode(tab("Transkript")).assertIsSelected()
        waitFor(hasText("Redebeiträge", substring = true))
        composeRule.onAllNodesWithText(UI_AUDIO_TITLE)[0].assertExists()
    }

    // ================================================================ Besprechung

    @Test
    fun meeting_tabsSwitch_andSourceChipShowsQuote() {
        openMeeting(UI_AUDIO_TITLE)
        composeRule.onNode(tab("Zusammenfassung")).assertIsSelected()
        waitFor(hasText("Überblick"))

        composeRule.onNode(tab("Transkript")).performClick()
        composeRule.onNode(tab("Transkript")).assertIsSelected()
        waitFor(hasText("${seed.audio.segments.size} Redebeiträge", substring = true))

        composeRule.onNode(tab("Aufgaben")).performClick()
        composeRule.onNode(tab("Aufgaben")).assertIsSelected()
        waitFor(hasText("0 von ${seed.audio.tasks.size} erledigt"))

        composeRule.onNode(tab("Zusammenfassung")).performClick()
        composeRule.onNode(tab("Zusammenfassung")).assertIsSelected()

        val chip = hasContentDescription("Quelle abspielen") and hasClickAction()
        scrollTo(chip)
        // Nur einen tatsächlich sichtbaren Beleg antippen (die Liste hält auch Elemente außerhalb vor)
        val all = composeRule.onAllNodes(chip)
        val visible = (0 until all.fetchSemanticsNodes().size).first { i ->
            runCatching { all[i].assertIsDisplayed() }.isSuccess
        }
        all[visible].performClick()
        waitFor(hasText("Im Transkript zeigen"), timeoutMs = 20_000, unmerged = true)
        composeRule.onNodeWithText("Zitat", substring = true).assertExists()
        assertTrue("Zitatkarte zeigt keinen Redebeitrag der Besprechung",
            seed.audio.segments.any { exists(hasText("„${it.text}“")) })

        composeRule.onNodeWithText("Im Transkript zeigen").performClick()
        composeRule.onNode(tab("Transkript")).assertIsSelected()
    }

    @Test
    fun meeting_templateChipAndMenu_showAllFiveTemplates_andApply() {
        openMeeting(UI_AUDIO_TITLE)

        // 1) über das Etikett unter dem Titel
        composeRule.onNode(hasContentDescription("Vorlage ändern") and hasClickAction()).performClick()
        waitFor(hasText("Vorlage wählen"))
        ProtocolTemplate.entries.forEach { composeRule.onNodeWithText(it.description).assertExists() }
        composeRule.onNodeWithText(ProtocolTemplate.DIENSTBESPRECHUNG.description).performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            MeetingRepository.get(seed.audio.id)?.template == ProtocolTemplate.DIENSTBESPRECHUNG
        }
        waitFor(hasText("Ort, Leitung und Protokollführung ergänzen"))

        // 2) über das Menü „Mehr“ → „Vorlage ändern“
        scrollToTop()
        composeRule.onNodeWithContentDescription("Mehr").performClick()
        waitFor(hasText("Vorlage ändern"))
        composeRule.onNodeWithText("Vorlage ändern").performClick()
        waitFor(hasText("Vorlage wählen"))
        assertEquals(5, ProtocolTemplate.entries.size)
        ProtocolTemplate.entries.forEach {
            composeRule.onNodeWithText(it.description).assertExists()
            composeRule.onAllNodesWithText(it.label).fetchSemanticsNodes().let { nodes ->
                assertTrue("Vorlage „${it.label}“ fehlt", nodes.isNotEmpty())
            }
        }
        composeRule.onNodeWithText(ProtocolTemplate.VEREINSSITZUNG.description).performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            MeetingRepository.get(seed.audio.id)?.template == ProtocolTemplate.VEREINSSITZUNG
        }
    }

    // ================================================================ Aufgaben

    @Test
    fun tasksOverview_groupsByPerson_checkOffAndUndo() {
        waitFor(hasText("Aufgaben · $UI_OPEN_TASKS"))
        composeRule.onNodeWithText("Aufgaben · $UI_OPEN_TASKS").performClick()
        waitFor(hasText("$UI_OPEN_TASKS offene Aufgaben"))
        for (owner in listOf("Frau Becker", "Herr Wagner", "Ohne Zuständigkeit")) {
            scrollTo(hasText(owner))
            composeRule.onNodeWithText(owner).assertExists()
        }

        fun taskDone() = MeetingRepository.get(seed.audio.id)!!.tasks.first { it.text == UI_TASK_BECKER }.done
        val checkbox = isToggleable() and hasAnySibling(hasText(UI_TASK_BECKER))
        scrollTo(hasText(UI_TASK_BECKER))
        composeRule.onNode(checkbox, useUnmergedTree = true).assertIsOff()

        // Uhr anhalten, damit die Snackbar mit „Rückgängig“ sicher sichtbar bleibt
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNode(checkbox, useUnmergedTree = true).performClick()
            composeRule.mainClock.advanceTimeBy(1_000)
            assertTrue("Abhaken nicht gespeichert", taskDone())
            composeRule.onNodeWithText("Erledigt: ", substring = true).assertExists()
            composeRule.onNodeWithText("Rückgängig").performClick()
            composeRule.mainClock.advanceTimeBy(1_000)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitUntil(5_000) { !taskDone() }
        waitFor(hasText("$UI_OPEN_TASKS offene Aufgaben"))
        scrollTo(hasText(UI_TASK_BECKER))
        composeRule.onNode(checkbox, useUnmergedTree = true).assertIsOff()
    }

    // ================================================================ Einstellungen

    @Test
    fun settings_vocabularyChipEditor_andScreenshotBlocking_persist() {
        val prefs = TestEnv.context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        waitFor(hasContentDescription("Einstellungen"))
        composeRule.onNodeWithContentDescription("Einstellungen").performClick()
        waitFor(hasText("Standard-Protokollart"))

        val placeholder = "Begriff hinzufügen (mehrere mit Komma)"
        scrollTo(hasText(placeholder))
        composeRule.onNode(hasSetTextAction() and hasText(placeholder)).performTextInput("Kolbenfresser")
        composeRule.onNode(hasSetTextAction() and hasText("Kolbenfresser")).performImeAction()
        composeRule.waitUntil(5_000) { AppSettings.state.value.vocabulary == listOf("Kolbenfresser") }
        composeRule.onNodeWithContentDescription("„Kolbenfresser“ entfernen").assertExists()
        assertEquals("Kolbenfresser", prefs.getString("vocabulary", null))

        // mehrere mit Komma, Duplikat wird ignoriert
        composeRule.onNode(hasSetTextAction() and hasText(placeholder))
            .performTextInput("Pflegegrad, SGB XI, kolbenfresser")
        composeRule.onNode(hasSetTextAction() and hasText("Pflegegrad", substring = true)).performImeAction()
        composeRule.waitUntil(5_000) {
            AppSettings.state.value.vocabulary == listOf("Kolbenfresser", "Pflegegrad", "SGB XI")
        }
        // Chip antippen entfernt den Begriff
        composeRule.onNodeWithContentDescription("„Pflegegrad“ entfernen").performClick()
        composeRule.waitUntil(5_000) { AppSettings.state.value.vocabulary == listOf("Kolbenfresser", "SGB XI") }
        assertEquals("Kolbenfresser\nSGB XI", prefs.getString("vocabulary", null))

        val toggle = hasText("Screenshots blockieren") and isToggleable()
        scrollTo(toggle)
        assertFalse(secureFlagSet())
        composeRule.onNode(toggle).assertIsOff().performClick()
        composeRule.onNode(toggle).assertIsOn()
        assertTrue(AppSettings.state.value.blockScreenshots)
        assertTrue(prefs.getBoolean("blockScreenshots", false))
        composeRule.waitUntil(5_000) { secureFlagSet() }

        composeRule.onNode(toggle).performClick()
        composeRule.onNode(toggle).assertIsOff()
        assertFalse(AppSettings.state.value.blockScreenshots)
        composeRule.waitUntil(5_000) { !secureFlagSet() }
    }

    // ================================================================ Importiertes Protokoll

    @Test
    fun importedProtocol_showsImportedLabel_andNoAudioPlayer() {
        // Das importierte Protokoll ist älter und steht weiter unten: erst scrollen, dann prüfen
        waitFor(hasText(UI_AUDIO_TITLE))
        scrollTo(hasText(UI_IMPORTED_TITLE))
        waitFor(hasText(UI_IMPORTED_TITLE))
        composeRule.onNode(hasText(UI_IMPORTED_TITLE) and hasText("Importiert")).assertExists()
        composeRule.onAllNodes(hasText(UI_AUDIO_TITLE) and hasText("Importiert")).assertCountEquals(0)

        openMeeting(UI_IMPORTED_TITLE)
        waitFor(hasText("Importiert · $UI_IMPORTED_FILE"))
        composeRule.onNode(tab("Originaltext")).assertExists()
        composeRule.onAllNodes(tab("Transkript")).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Abspielen").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("10 Sekunden zurück").assertCountEquals(0)
        composeRule.onAllNodesWithText("Teilnehmende · zum Umbenennen antippen").assertCountEquals(0)

        // Gegenprobe: die Besprechung mit Aufnahme hat den Player (sonst wäre der Test wertlos)
        composeRule.onNodeWithContentDescription("Zurück").performClick()
        openMeeting(UI_AUDIO_TITLE)
        waitFor(hasContentDescription("Abspielen"))
        composeRule.onNodeWithContentDescription("10 Sekunden zurück").assertExists()
    }

    // ================================================================ Drehen / Neuaufbau

    @Test
    fun meetingScreen_survivesRecreationAndRotation_keepingSelectedTab() {
        openMeeting(UI_AUDIO_TITLE)
        composeRule.onNode(tab("Aufgaben")).performClick()
        composeRule.onNode(tab("Aufgaben")).assertIsSelected()

        composeRule.activityRule.scenario.recreate()
        waitFor(tab("Aufgaben") and isSelected(), timeoutMs = 15_000)
        waitFor(hasText("0 von ${seed.audio.tasks.size} erledigt"))

        val scenario = composeRule.activityRule.scenario
        try {
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            composeRule.waitUntil(15_000) {
                TestEnv.context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            // Im Querformat können die Reiter unterhalb des sichtbaren Bereichs liegen
            scrollTo(tab("Aufgaben"))
            waitFor(tab("Aufgaben") and isSelected(), timeoutMs = 15_000)
            composeRule.onNode(tab("Aufgaben")).assertIsSelected()
        } finally {
            runCatching {
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        }
        waitFor(tab("Aufgaben") and isSelected(), timeoutMs = 15_000)
    }
}
