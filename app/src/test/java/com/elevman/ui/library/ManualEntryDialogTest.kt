package com.elevman.ui.library

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.ui.theme.ElevManTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two entry forms on their own: what they check before they ask for a save, and what they
 * keep when a save fails. Default screen size: a dialog with text fields never goes idle on a tall one.
 */
@RunWith(AndroidJUnit4::class)
class ManualEntryDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private val codeEntry = ManualEntry.Code("man-1", "Service manual")
    private val taskEntry = ManualEntry.Task("man-1", "Service manual")

    /** What the forms reported, so a test can say what was and was not asked for. */
    private class Asked {
        val codes = mutableListOf<NewCode>()
        val tasks = mutableListOf<NewTask>()
        var dismissed = 0
    }

    private val saveError = mutableStateOf<String?>(null)

    private fun showCode(asked: Asked = Asked()): Asked {
        rule.setContent {
            ElevManTheme {
                AddCodeDialog(
                    entry = codeEntry,
                    onSave = { asked.codes += it },
                    onDismiss = { asked.dismissed++ },
                    error = saveError.value,
                )
            }
        }
        return asked
    }

    private fun showTask(asked: Asked = Asked()): Asked {
        rule.setContent {
            ElevManTheme {
                AddTaskDialog(
                    entry = taskEntry,
                    onSave = { asked.tasks += it },
                    onDismiss = { asked.dismissed++ },
                    error = saveError.value,
                )
            }
        }
        return asked
    }

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    // ---- The error code form ----------------------------------------------------------------

    @Test
    fun theCodeFormNamesTheManualAndAsksForCodeMeaningCauseAndPage() {
        showCode()

        rule.onNodeWithText("Add error code").assertIsDisplayed()
        rule.onNodeWithText("Manual: Service manual").assertIsDisplayed()
        field("Code").assertIsDisplayed()
        field("Meaning").assertIsDisplayed()
        field("Cause and fix").assertIsDisplayed()
        field("Page (optional)").assertIsDisplayed()
    }

    @Test
    fun savingACodeReturnsWhatWasTyped() {
        val asked = showCode()

        field("Code").performTextInput(" e-22 ")
        field("Meaning").performTextInput("Door lock fault")
        field("Cause and fix").performTextInput("Replace the lock")
        field("Page (optional)").performTextInput("12")
        rule.onNodeWithText("Save").performClick()

        assertEquals(listOf(NewCode("e-22", "Door lock fault", "Replace the lock", 12)), asked.codes)
    }

    @Test
    fun aCodeWithOnlyACodeAndAMeaningSavesPageZeroAndNoCause() {
        val asked = showCode()

        field("Code").performTextInput("E21")
        field("Meaning").performTextInput("Door zone fault")
        rule.onNodeWithText("Save").performClick()

        assertEquals(listOf(NewCode("E21", "Door zone fault", "", 0)), asked.codes)
    }

    @Test
    fun aBlankCodeIsRejectedBeforeAnythingIsSaved() {
        val asked = showCode()

        field("Meaning").performTextInput("Door zone fault")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Enter the code.").assertIsDisplayed()

        // Spaces are blank too.
        field("Code").performTextInput("   ")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Enter the code.").assertIsDisplayed()

        assertTrue(asked.codes.isEmpty())
        assertEquals(0, asked.dismissed)
    }

    @Test
    fun aCodeWithNoLetterOrDigitIsRejectedLikeABlankOne() {
        val asked = showCode()

        // Normalized, it is empty: a search for it could never find it.
        field("Code").performTextInput("--")
        field("Meaning").performTextInput("Door zone fault")
        rule.onNodeWithText("Save").performClick()

        rule.onNodeWithText("Enter the code.").assertIsDisplayed()
        assertTrue(asked.codes.isEmpty())
    }

    @Test
    fun aBlankMeaningIsRejectedBeforeAnythingIsSaved() {
        val asked = showCode()

        field("Code").performTextInput("E21")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Enter the meaning.").assertIsDisplayed()

        field("Meaning").performTextInput("  ")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Enter the meaning.").assertIsDisplayed()

        rule.onNodeWithText("Enter the code.").assertDoesNotExist()
        assertTrue(asked.codes.isEmpty())
        assertEquals(0, asked.dismissed)
    }

    @Test
    fun aBlankCodeAndAMeaningAreBothReported() {
        val asked = showCode()

        rule.onNodeWithText("Save").performClick()

        rule.onNodeWithText("Enter the code.").assertIsDisplayed()
        rule.onNodeWithText("Enter the meaning.").assertIsDisplayed()
        assertTrue(asked.codes.isEmpty())
    }

    @Test
    fun aCodeMessageGoesOnceThatFieldIsTyped() {
        showCode()
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Enter the code.").assertIsDisplayed()
        rule.onNodeWithText("Enter the meaning.").assertIsDisplayed()

        field("Code").performTextInput("E21")
        rule.onNodeWithText("Enter the code.").assertDoesNotExist()
        rule.onNodeWithText("Enter the meaning.").assertIsDisplayed()

        field("Meaning").performTextInput("Door zone fault")
        rule.onNodeWithText("Enter the meaning.").assertDoesNotExist()
    }

    @Test
    fun thePageFieldTakesDigitsOnly() {
        val asked = showCode()

        field("Code").performTextInput("E21")
        field("Meaning").performTextInput("Door zone fault")
        field("Page (optional)").performTextInput("1a2-3")
        rule.onNodeWithText("123").assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()

        assertEquals(123, asked.codes.single().pageNumber)
    }

    @Test
    fun aFailedCodeSaveShowsItsMessageInsideTheFormAndKeepsWhatWasTyped() {
        val asked = showCode()
        field("Code").performTextInput("E-22")
        field("Meaning").performTextInput("Door lock fault")
        field("Page (optional)").performTextInput("12")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText(ENTRY_FAILED_MESSAGE).assertDoesNotExist()

        // The save was refused: the screen tells the form.
        rule.runOnUiThread { saveError.value = ENTRY_FAILED_MESSAGE }
        rule.waitForIdle()

        rule.onNodeWithText(ENTRY_FAILED_MESSAGE).assertIsDisplayed()
        rule.onNodeWithText("E-22").assertIsDisplayed()
        rule.onNodeWithText("Door lock fault").assertIsDisplayed()
        rule.onNodeWithText("12").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed()
        assertEquals(0, asked.dismissed)

        // Save can be tried again with the same values.
        rule.onNodeWithText("Save").performClick()
        assertEquals(2, asked.codes.size)
        assertEquals(asked.codes[0], asked.codes[1])
    }

    @Test
    fun cancelOnTheCodeFormSavesNothing() {
        val asked = showCode()

        field("Code").performTextInput("X")
        rule.onNodeWithText("Cancel").performClick()

        assertTrue(asked.codes.isEmpty())
        assertEquals(1, asked.dismissed)
    }

    // ---- The maintenance task form ----------------------------------------------------------

    @Test
    fun theTaskFormNamesTheManualAndAsksForNameIntervalAndPage() {
        showTask()

        rule.onNodeWithText("Add maintenance task").assertIsDisplayed()
        rule.onNodeWithText("Manual: Service manual").assertIsDisplayed()
        field("Name").assertIsDisplayed()
        field("Interval (months)").assertIsDisplayed()
        field("Page (optional)").assertIsDisplayed()
    }

    @Test
    fun savingATaskReturnsWhatWasTyped() {
        val asked = showTask()

        field("Name").performTextInput(" Inspect door rollers ")
        field("Interval (months)").performTextInput("12")
        field("Page (optional)").performTextInput("40")
        rule.onNodeWithText("Save").performClick()

        assertEquals(listOf(NewTask("Inspect door rollers", 12, 40)), asked.tasks)
    }

    @Test
    fun aTaskWithoutAPageSavesPageZero() {
        val asked = showTask()

        field("Name").performTextInput("Check brake")
        field("Interval (months)").performTextInput("1")
        rule.onNodeWithText("Save").performClick()

        assertEquals(listOf(NewTask("Check brake", 1, 0)), asked.tasks)
        rule.onNodeWithText("Interval must be at least 1 month.").assertDoesNotExist()
    }

    @Test
    fun anIntervalBelowOneIsRejectedWithTheEditDialogsMessageBeforeAnythingIsSaved() {
        val asked = showTask()
        field("Name").performTextInput("Check brake")

        for (bad in listOf("0", "-3", "")) {
            field("Interval (months)").performTextClearance()
            if (bad.isNotEmpty()) field("Interval (months)").performTextInput(bad)
            rule.onNodeWithText("Save").performClick()

            rule.onNodeWithText("Interval must be at least 1 month.").assertIsDisplayed()
            assertTrue("saved with interval '$bad'", asked.tasks.isEmpty())
        }
        assertEquals(0, asked.dismissed)
    }

    @Test
    fun theIntervalMessageGoesOnceTheNumberIsFixed() {
        showTask()
        field("Name").performTextInput("Check brake")
        field("Interval (months)").performTextInput("0")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Interval must be at least 1 month.").assertIsDisplayed()

        field("Interval (months)").performTextClearance()
        field("Interval (months)").performTextInput("4")

        rule.onNodeWithText("Interval must be at least 1 month.").assertDoesNotExist()
    }

    @Test
    fun aBlankTaskNameIsRejectedBeforeAnythingIsSaved() {
        val asked = showTask()

        field("Interval (months)").performTextInput("6")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Enter the task name.").assertIsDisplayed()

        field("Name").performTextInput("   ")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Enter the task name.").assertIsDisplayed()

        assertTrue(asked.tasks.isEmpty())
        assertEquals(0, asked.dismissed)
    }

    @Test
    fun aFailedTaskSaveShowsItsMessageInsideTheFormAndKeepsWhatWasTyped() {
        showTask()
        field("Name").performTextInput("Inspect door rollers")
        field("Interval (months)").performTextInput("12")
        rule.onNodeWithText("Save").performClick()

        rule.runOnUiThread { saveError.value = ENTRY_FAILED_MESSAGE }
        rule.waitForIdle()

        rule.onNodeWithText(ENTRY_FAILED_MESSAGE).assertIsDisplayed()
        rule.onNodeWithText("Inspect door rollers").assertIsDisplayed()
        rule.onNodeWithText("12").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun cancelOnTheTaskFormSavesNothing() {
        val asked = showTask()

        field("Name").performTextInput("X")
        rule.onNodeWithText("Cancel").performClick()

        assertTrue(asked.tasks.isEmpty())
        assertEquals(1, asked.dismissed)
    }
}
