package com.elevman.ui.elevators

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.data.DueItem
import com.elevman.ui.library.ModelChoice
import com.elevman.ui.theme.ElevManTheme
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// A tall screen, so that a whole page is on screen: a lazy list only builds what is visible. The note
// dialog has a text field, which never goes idle on a screen that tall: see AddNoteTest.
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h1200dp")
class ElevatorsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val today = LocalDate.of(2026, 10, 16)

    // ---- The list, grouped by site -------------------------------------------------------------

    private val groups = listOf(
        SiteGroup(
            "Harbour office",
            listOf(ElevatorSummary("e1", "Otis Gen2", "SN-100", "Acme")),
        ),
        SiteGroup(
            "Port tower",
            listOf(
                ElevatorSummary("e2", "Otis Alpha", "SN-200", "Beta Ltd"),
                ElevatorSummary("e3", "Kone MonoSpace", "SN-300", "Beta Ltd"),
            ),
        ),
    )

    private fun showList(
        state: ElevatorUiState = ElevatorUiState(loaded = true, groups = groups),
        opened: MutableList<String> = mutableListOf(),
        adds: MutableList<Unit> = mutableListOf(),
    ) {
        rule.setContent {
            ElevManTheme { ElevatorsScreen(state, onOpen = { opened += it }, onAdd = { adds += Unit }) }
        }
    }

    @Test
    fun elevatorsAreListedUnderTheirSite() {
        showList()

        rule.onNodeWithText("Harbour office").assertIsDisplayed()
        rule.onNodeWithText("Port tower").assertIsDisplayed()
        rule.onNodeWithText("Otis Gen2").assertIsDisplayed()
        rule.onNodeWithText("Otis Alpha").assertIsDisplayed()
        rule.onNodeWithText("Kone MonoSpace").assertIsDisplayed()
        rule.onNodeWithText("Serial SN-200", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingAnElevatorOpensIt() {
        val opened = mutableListOf<String>()
        showList(opened = opened)

        rule.onNodeWithText("Otis Alpha").performClick()

        assertEquals(listOf("e2"), opened)
    }

    @Test
    fun theAddElevatorButtonOpensTheForm() {
        val adds = mutableListOf<Unit>()
        showList(adds = adds)

        rule.onNodeWithText("Add elevator").performClick()

        assertEquals(1, adds.size)
    }

    @Test
    fun anEmptyListTellsHowToStart() {
        showList(ElevatorUiState(loaded = true))

        rule.onNodeWithText(ELEVATORS_EMPTY_MESSAGE).assertIsDisplayed()
        rule.onNodeWithText("Add elevator").assertIsDisplayed()
    }

    // ---- The detail ----------------------------------------------------------------------------

    private val overdueTask = DueItem("et-1", "Check brake", LocalDate.of(2026, 9, 1), overdue = true)
    private val laterTask = DueItem("et-2", "Oil guide rails", LocalDate.of(2026, 12, 1), overdue = false)

    private val detail = ElevatorDetail(
        id = "e1",
        site = "Harbour office",
        customer = "Acme",
        modelName = "Otis Gen2",
        serialNumber = "SN-100",
        notes = "Machine room on the roof",
        tasks = listOf(overdueTask, laterTask),
        log = listOf(
            LogLine("l2", LocalDate.of(2026, 10, 1), "Check brake", "Pads at 60 percent"),
            LogLine("l1", LocalDate.of(2026, 3, 1), null, "Handed over"),
        ),
        assignable = listOf(TaskChoice("t-alarm", "Test alarm bell", 3), TaskChoice("t-door", "Check door roller", 12)),
    )

    private class Taps {
        val done = mutableListOf<Pair<String, LocalDate>>()
        val notes = mutableListOf<Pair<LocalDate, String>>()
        val assigned = mutableListOf<String>()
    }

    // [accept] is what the view model answers: false means it refused the change.
    private fun showDetail(
        detail: ElevatorDetail? = this.detail,
        loaded: Boolean = true,
        taps: Taps = Taps(),
        accept: Boolean = true,
    ): Taps {
        rule.setContent {
            ElevManTheme {
                ElevatorDetailScreen(
                    detail = detail,
                    loaded = loaded,
                    today = { today },
                    onMarkDone = { id, date ->
                        taps.done += id to date
                        accept
                    },
                    onAddNote = { date, note ->
                        taps.notes += date to note
                        accept
                    },
                    onAssign = {
                        taps.assigned += it
                        accept
                    },
                )
            }
        }
        return taps
    }

    @Test
    fun theDetailShowsTheModelSerialNumberTasksWithDueDatesAndTheLog() {
        showDetail()

        rule.onNodeWithText("Otis Gen2").assertIsDisplayed()
        rule.onNodeWithText("SN-100", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Harbour office", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Check brake").assertIsDisplayed()
        rule.onNodeWithText("Due 2026-09-01").assertIsDisplayed()
        rule.onNodeWithText("Oil guide rails").assertIsDisplayed()
        rule.onNodeWithText("Due 2026-12-01").assertIsDisplayed()
        rule.onNodeWithText("Pads at 60 percent").assertIsDisplayed()
        rule.onNodeWithText("Handed over").assertIsDisplayed()
        rule.onNodeWithText("2026-10-01", substring = true).assertIsDisplayed()
    }

    @Test
    fun anOverdueTaskIsLabelledWithTheWordOverdue() {
        showDetail()

        // One of the two tasks is overdue, and the label is text, not only a colour.
        rule.onAllNodesWithText("Overdue").assertCountEquals(1)
    }

    @Test
    fun noTaskIsLabelledOverdueWhenNoneIs() {
        showDetail(detail.copy(tasks = listOf(laterTask)))

        rule.onAllNodesWithText("Overdue").assertCountEquals(0)
    }

    @Test
    fun aDetailWithNoTasksOrLogSaysSo() {
        showDetail(detail.copy(tasks = emptyList(), log = emptyList()))

        rule.onNodeWithText("No tasks assigned yet.").assertIsDisplayed()
        rule.onNodeWithText("No log entries yet.").assertIsDisplayed()
    }

    @Test
    fun anElevatorThatIsGoneSaysSo() {
        showDetail(detail = null, loaded = true)

        rule.onNodeWithText(ELEVATOR_GONE_MESSAGE).assertIsDisplayed()
    }

    // ---- Assign task ---------------------------------------------------------------------------

    @Test
    fun assignTaskListsTheChoicesAndPickingOneAssignsIt() {
        val taps = showDetail()

        rule.onNodeWithText("Assign task").performClick()
        rule.onNodeWithText("Test alarm bell", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Check door roller", substring = true).performClick()

        assertEquals(listOf("t-door"), taps.assigned)
        // The dialog closed.
        rule.onAllNodesWithText("Test alarm bell", substring = true).assertCountEquals(0)
    }

    @Test
    fun theAssignDialogStaysOpenWhenTheChangeIsRefused() {
        val taps = showDetail(accept = false)

        rule.onNodeWithText("Assign task").performClick()
        rule.onNodeWithText("Check door roller", substring = true).performClick()

        assertEquals(listOf("t-door"), taps.assigned)
        // Refused (say, another save is running): the choices are still there to tap again.
        rule.onNodeWithText("Check door roller", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Test alarm bell", substring = true).assertIsDisplayed()
    }

    @Test
    fun assignTaskWithNothingLeftToAssignSaysSo() {
        showDetail(detail.copy(assignable = emptyList()))

        rule.onNodeWithText("Assign task").performClick()

        rule.onNodeWithText(NOTHING_TO_ASSIGN_MESSAGE).assertIsDisplayed()
    }

    // ---- Mark done: the date picker -------------------------------------------------------------

    @Test
    fun markDoneOpensTheDatePickerAndConfirmingSendsTodayByDefault() {
        val taps = showDetail()

        rule.onAllNodesWithText("Mark done")[0].performClick()
        rule.onNodeWithText("OK").assertIsEnabled().performClick()

        // The overdue task is listed first; today is what the picker starts on.
        assertEquals(listOf("et-1" to today), taps.done)
    }

    @Test
    fun theDatePickerStartsOnTodayAndDisablesEveryLaterDay() {
        showDetail()

        rule.onAllNodesWithText("Mark done")[0].performClick()

        // Today is Friday 16 October 2026: it is the selection, and the days after it cannot be chosen.
        rule.onNodeWithText("Oct 16, 2026").assertIsDisplayed()
        rule.onNodeWithText("October 16, 2026", substring = true).assertIsEnabled()
        rule.onNodeWithText("October 15, 2026", substring = true).assertIsEnabled()
        rule.onNodeWithText("October 17, 2026", substring = true).assertIsNotEnabled()
        rule.onNodeWithText("October 31, 2026", substring = true).assertIsNotEnabled()
    }

    @Test
    fun theDatePickerStaysOpenWhenTheChangeIsRefused() {
        val taps = showDetail(accept = false)

        rule.onAllNodesWithText("Mark done")[0].performClick()
        rule.onNodeWithText("OK").performClick()

        assertEquals(listOf("et-1" to today), taps.done)
        rule.onNodeWithText("OK").assertIsDisplayed()
    }

    @Test
    fun cancellingTheDatePickerSendsNothing() {
        val taps = showDetail()

        rule.onAllNodesWithText("Mark done")[0].performClick()
        rule.onNodeWithText("Cancel").performClick()

        assertTrue(taps.done.isEmpty())
        rule.onAllNodesWithText("OK").assertCountEquals(0)
    }

    private fun utcMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun theDatePickerOnlyAllowsDatesUpToToday() {
        val allowed = NotAfter(today)

        assertTrue(allowed.isSelectableDate(utcMillis(today)))
        assertTrue(allowed.isSelectableDate(utcMillis(today.minusDays(40))))
        assertFalse(allowed.isSelectableDate(utcMillis(today.plusDays(1))))
        assertFalse(allowed.isSelectableDate(utcMillis(today.plusYears(1))))
        assertTrue(allowed.isSelectableYear(today.year))
        assertFalse(allowed.isSelectableYear(today.year + 1))
    }

    // ---- Add elevator --------------------------------------------------------------------------

    private class Saved {
        val rows = mutableListOf<List<String>>()
    }

    private val models = listOf(ModelChoice("m-gen2", "Otis Gen2"), ModelChoice("m-alpha", "Otis Alpha"))

    private fun showForm(models: List<ModelChoice> = this.models, saved: Saved = Saved()): Saved {
        rule.setContent {
            ElevManTheme {
                AddElevatorScreen(
                    models = models,
                    busy = false,
                    onSave = { site, customer, modelId, serial, notes -> saved.rows += listOf(site, customer, modelId, serial, notes) },
                )
            }
        }
        return saved
    }

    @Test
    fun saveStaysOffUntilTheSiteAndAModelAreGiven() {
        showForm()

        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNode(hasText("Site") and hasSetTextAction()).performTextInput("Harbour office")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        rule.onNodeWithText("Otis Alpha").performClick()
        rule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun saveSendsAllFiveFields() {
        val saved = showForm()

        rule.onNode(hasText("Site") and hasSetTextAction()).performTextInput("Harbour office")
        rule.onNode(hasText("Customer") and hasSetTextAction()).performTextInput("Acme")
        rule.onNodeWithText("Otis Alpha").performClick()
        rule.onNode(hasText("Serial number") and hasSetTextAction()).performTextInput("SN-77")
        rule.onNode(hasText("Notes") and hasSetTextAction()).performTextInput("Roof")
        rule.onNodeWithText("Save").performClick()

        assertEquals(listOf(listOf("Harbour office", "Acme", "m-alpha", "SN-77", "Roof")), saved.rows)
    }

    @Test
    fun withoutAnyModelTheFormSaysWhereToGetOne() {
        showForm(models = emptyList())

        rule.onNodeWithText(NO_MODELS_MESSAGE).assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }
}
