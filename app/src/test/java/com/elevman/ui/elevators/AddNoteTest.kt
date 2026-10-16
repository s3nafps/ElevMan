package com.elevman.ui.elevators

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.ui.theme.ElevManTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Add note dialog, on the default screen size: a dialog with a text field never goes idle on a
 * tall one (the same limit as the review's edit dialog), so these are kept apart from the screen tests.
 * The detail has no tasks and no log, so its Add note button is on screen even though the screen is small.
 */
@RunWith(AndroidJUnit4::class)
class AddNoteTest {

    @get:Rule
    val rule = createComposeRule()

    private val today = LocalDate.of(2026, 10, 16)

    private val detail = ElevatorDetail(
        id = "e1",
        site = "Harbour office",
        customer = "",
        modelName = "Otis Gen2",
        serialNumber = "SN-100",
        notes = "",
        tasks = emptyList(),
        log = emptyList(),
        assignable = emptyList(),
    )

    private fun show(notes: MutableList<Pair<LocalDate, String>>) {
        rule.setContent {
            ElevManTheme {
                ElevatorDetailScreen(
                    detail = detail,
                    loaded = true,
                    today = { today },
                    onMarkDone = { _, _ -> },
                    onAddNote = { date, note -> notes += date to note },
                    onAssign = {},
                )
            }
        }
    }

    @Test
    fun addNoteSendsTheTextDatedToday() {
        val notes = mutableListOf<Pair<LocalDate, String>>()
        show(notes)

        rule.onNodeWithText("Add note").performClick()
        rule.onNodeWithText("Date: 2026-10-16").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextInput("Replaced the door roller")
        rule.onNodeWithText("Save").performClick()

        assertEquals(listOf(today to "Replaced the door roller"), notes)
    }

    @Test
    fun addNoteCannotBeSavedWithoutText() {
        val notes = mutableListOf<Pair<LocalDate, String>>()
        show(notes)

        rule.onNodeWithText("Add note").performClick()

        rule.onNodeWithText("Save").assertIsNotEnabled()
        assertTrue(notes.isEmpty())
    }

    @Test
    fun cancellingTheNoteSendsNothingAndClosesTheDialog() {
        val notes = mutableListOf<Pair<LocalDate, String>>()
        show(notes)
        rule.onNodeWithText("Add note").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Never mind")

        rule.onNodeWithText("Cancel").performClick()

        assertTrue(notes.isEmpty())
        rule.onNodeWithText("Save").assertDoesNotExist()
    }
}
