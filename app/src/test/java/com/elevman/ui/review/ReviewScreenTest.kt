package com.elevman.ui.review

import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.data.ImportResult
import com.elevman.data.ItemKind
import com.elevman.data.ReviewItem
import com.elevman.ui.library.ImportNotice
import com.elevman.ui.library.ImportNoticeView
import com.elevman.ui.library.toNotice
import com.elevman.ui.theme.ElevManTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The review screen and its dialogs, and the messages the import flow shows. The page thumbnail
 * is not here: it needs a real PdfRenderer, so the screen takes it as a slot and the tests leave
 * the placeholder in.
 */
@RunWith(AndroidJUnit4::class)
class ReviewScreenTest {

    // The tests that count cards use a tall screen, so that all three cards are on screen: a lazy list
    // only builds what is visible. Not for every test: a dialog with text fields never goes idle on it.
    private companion object {
        const val TALL = "w360dp-h1600dp"
    }


    @get:Rule
    val rule = createComposeRule()

    private val door = ReviewItem(ItemKind.ERROR_CODE, "c1", "E-21", "Door fault", 12)
    private val lock = ReviewItem(ItemKind.ERROR_CODE, "c2", "E-22", "Door lock fault", 12)
    private val rails = ReviewItem(ItemKind.MAINTENANCE_TASK, "t1", "Lubricate guide rails", "every 6 months", 40)
    private val three = listOf(door, lock, rails)

    /** Records what the screen reported, so a test can say what was and was not called. */
    private class Calls {
        val accepted = mutableListOf<ReviewItem>()
        val edited = mutableListOf<ReviewItem>()
        val rejected = mutableListOf<ReviewItem>()
        var acceptAll = 0
    }

    private fun show(items: List<ReviewItem>, calls: Calls = Calls()): Calls {
        rule.setContent {
            ElevManTheme {
                ReviewScreen(
                    items = items,
                    onAccept = { calls.accepted += it },
                    onEdit = { calls.edited += it },
                    onReject = { calls.rejected += it },
                    onAcceptAll = { calls.acceptAll++ },
                )
            }
        }
        return calls
    }

    // ---- Accept all -------------------------------------------------------------------------

    @Test
    @Config(qualifiers = TALL)
    fun acceptAllNeedsConfirmation() {
        val calls = show(three)

        rule.onNodeWithText("Accept all").performClick()

        rule.onNodeWithText("Confirm 3 suggestions", substring = true).assertIsDisplayed()
        // Asking is not doing: nothing has been accepted yet.
        assertEquals(0, calls.acceptAll)

        rule.onNodeWithText("Cancel").performClick()

        rule.onNodeWithText("Confirm 3 suggestions", substring = true).assertDoesNotExist()
        rule.onAllNodesWithText("Suggested").assertCountEquals(3)
        assertEquals(0, calls.acceptAll)
    }

    @Test
    fun theConfirmQuestionReadsExactly() {
        show(three)

        rule.onNodeWithText("Accept all").performClick()

        rule.onNodeWithText("Confirm 3 suggestions?").assertIsDisplayed()
        rule.onNodeWithText("Confirm").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun confirmingCallsAcceptAllOnceAndClosesTheDialog() {
        val calls = show(three)

        rule.onNodeWithText("Accept all").performClick()
        rule.onNodeWithText("Confirm").performClick()

        assertEquals(1, calls.acceptAll)
        rule.onNodeWithText("Confirm 3 suggestions", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = TALL)
    fun cancelOnTheConfirmDialogChangesNothing() {
        val calls = show(three)

        rule.onNodeWithText("Accept all").performClick()
        rule.onNodeWithText("Cancel").performClick()

        assertEquals(0, calls.acceptAll)
        assertTrue(calls.accepted.isEmpty())
        assertTrue(calls.edited.isEmpty())
        assertTrue(calls.rejected.isEmpty())
        rule.onNodeWithText("Confirm", substring = true).assertDoesNotExist()
        // Every card is still there.
        rule.onAllNodesWithText("Suggested").assertCountEquals(3)
        rule.onNodeWithText("E-21").assertIsDisplayed()
        rule.onNodeWithText("E-22").assertIsDisplayed()
        rule.onNodeWithText("Lubricate guide rails").assertIsDisplayed()
    }

    @Test
    fun theConfirmQuestionCountsTheSuggestionsOnScreen() {
        show(listOf(door, lock))

        rule.onNodeWithText("Accept all").performClick()

        rule.onNodeWithText("Confirm 2 suggestions?").assertIsDisplayed()
    }

    @Test
    fun theConfirmQuestionOfOneSuggestionIsSingular() {
        show(listOf(door))

        rule.onNodeWithText("Accept all").performClick()

        rule.onNodeWithText("Confirm 1 suggestion?").assertIsDisplayed()
    }

    @Test
    fun acceptAllIsOffWhenNothingIsWaiting() {
        show(emptyList())

        rule.onNodeWithText("Accept all").assertIsNotEnabled()
        rule.onNodeWithText("Nothing is waiting for review.").assertIsDisplayed()
    }

    @Test
    fun acceptAllIsOnWhenSomethingIsWaiting() {
        show(three)

        rule.onNodeWithText("Accept all").assertIsEnabled()
    }

    // ---- The cards --------------------------------------------------------------------------

    @Test
    @Config(qualifiers = TALL)
    fun everyCardNamesItsItemItsPageAndThatItIsOnlySuggested() {
        show(three)

        rule.onNodeWithText("E-21").assertIsDisplayed()
        rule.onNodeWithText("Door fault").assertIsDisplayed()
        rule.onNodeWithText("Lubricate guide rails").assertIsDisplayed()
        rule.onNodeWithText("every 6 months").assertIsDisplayed()
        rule.onAllNodesWithText("Page 12", substring = true).assertCountEquals(2)
        rule.onAllNodesWithText("Page 40", substring = true).assertCountEquals(1)
        rule.onAllNodesWithText("Suggested").assertCountEquals(3)
    }

    @Test
    @Config(qualifiers = TALL)
    fun theButtonsOfACardReportThatCardsItem() {
        val calls = show(three)

        // Cards are in the order given, so the second Accept belongs to the second card.
        rule.onAllNodesWithText("Accept")[1].performClick()
        rule.onAllNodesWithText("Edit")[2].performClick()
        rule.onAllNodesWithText("Reject")[0].performClick()

        assertEquals(listOf(lock), calls.accepted)
        assertEquals(listOf(rails), calls.edited)
        assertEquals(listOf(door), calls.rejected)
        assertEquals(0, calls.acceptAll)
    }

    @Test
    @Config(qualifiers = TALL)
    fun theListKeepsTheOrderItWasGiven() {
        // Not sorted by page or label on purpose: the repository decides the order, not the screen.
        show(listOf(rails, lock, door))

        val tops = listOf("Lubricate guide rails", "E-22", "E-21").map {
            rule.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }

        assertEquals(tops.sorted(), tops)
    }

    // ---- The edit dialog --------------------------------------------------------------------

    private class Saved {
        val targets = mutableListOf<EditTarget>()
        var dismissed = 0
    }

    private val saveError = mutableStateOf<String?>(null)

    private fun showEdit(target: EditTarget, saved: Saved = Saved()): Saved {
        rule.setContent {
            ElevManTheme {
                EditDialog(
                    target = target,
                    onSave = { saved.targets += it },
                    onDismiss = { saved.dismissed++ },
                    error = saveError.value,
                )
            }
        }
        return saved
    }

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    @Test
    fun editingAnErrorCodeShowsCodeMeaningAndCauseAndFix() {
        showEdit(EditTarget.Code("c1", "E-21", "Door fault", "Check the sill"))

        field("Code").assertIsDisplayed()
        field("Meaning").assertIsDisplayed()
        field("Cause and fix").assertIsDisplayed()
        rule.onNodeWithText("E-21").assertIsDisplayed()
        rule.onNodeWithText("Door fault").assertIsDisplayed()
        rule.onNodeWithText("Check the sill").assertIsDisplayed()
    }

    @Test
    fun savingAnErrorCodeReturnsTheEditedFields() {
        val saved = showEdit(EditTarget.Code("c1", "E-21", "Door fault", ""))

        field("Code").performTextClearance()
        field("Code").performTextInput("E-22")
        field("Meaning").performTextClearance()
        field("Meaning").performTextInput("Door lock fault")
        field("Cause and fix").performTextInput("Replace the lock")
        rule.onNodeWithText("Save").performClick()

        assertEquals(listOf<EditTarget>(EditTarget.Code("c1", "E-22", "Door lock fault", "Replace the lock")), saved.targets)
    }

    @Test
    fun editingATaskShowsNameAndIntervalInMonths() {
        showEdit(EditTarget.Task("t1", "Lubricate guide rails", 6))

        field("Name").assertIsDisplayed()
        field("Interval (months)").assertIsDisplayed()
        rule.onNodeWithText("Lubricate guide rails").assertIsDisplayed()
        rule.onNodeWithText("6").assertIsDisplayed()
    }

    @Test
    fun savingATaskReturnsTheEditedNameAndInterval() {
        val saved = showEdit(EditTarget.Task("t1", "Lubricate guide rails", 6))

        field("Name").performTextClearance()
        field("Name").performTextInput("Inspect door rollers")
        field("Interval (months)").performTextClearance()
        field("Interval (months)").performTextInput("12")
        rule.onNodeWithText("Save").performClick()

        assertEquals(listOf<EditTarget>(EditTarget.Task("t1", "Inspect door rollers", 12)), saved.targets)
    }

    @Test
    fun anIntervalBelowOneIsRejectedInTheDialogBeforeSaving() {
        val saved = showEdit(EditTarget.Task("t1", "Lubricate guide rails", 6))

        for (bad in listOf("0", "-3", "")) {
            field("Interval (months)").performTextClearance()
            if (bad.isNotEmpty()) field("Interval (months)").performTextInput(bad)
            rule.onNodeWithText("Save").performClick()

            rule.onNodeWithText("Interval must be at least 1 month.").assertIsDisplayed()
            assertTrue("saved with interval '$bad'", saved.targets.isEmpty())
        }
        assertEquals(0, saved.dismissed)
    }

    @Test
    fun anIntervalOfOneIsAccepted() {
        val saved = showEdit(EditTarget.Task("t1", "Monthly check", 6))

        field("Interval (months)").performTextClearance()
        field("Interval (months)").performTextInput("1")
        rule.onNodeWithText("Save").performClick()

        rule.onNodeWithText("Interval must be at least 1 month.").assertDoesNotExist()
        assertEquals(listOf<EditTarget>(EditTarget.Task("t1", "Monthly check", 1)), saved.targets)
    }

    @Test
    fun theIntervalMessageGoesOnceTheNumberIsFixed() {
        showEdit(EditTarget.Task("t1", "Lubricate guide rails", 6))

        field("Interval (months)").performTextClearance()
        field("Interval (months)").performTextInput("0")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Interval must be at least 1 month.").assertIsDisplayed()

        field("Interval (months)").performTextClearance()
        field("Interval (months)").performTextInput("4")

        rule.onNodeWithText("Interval must be at least 1 month.").assertDoesNotExist()
    }

    @Test
    fun aFailedSaveShowsItsMessageInsideTheDialogAndKeepsWhatWasTyped() {
        showEdit(EditTarget.Code("c1", "E-21", "Door fault", ""))
        field("Meaning").performTextClearance()
        field("Meaning").performTextInput("Door lock fault")
        rule.onNodeWithText("That change could not be saved.").assertDoesNotExist()

        // The save was refused: the screen tells the dialog.
        rule.runOnUiThread { saveError.value = "That change could not be saved." }
        rule.waitForIdle()

        rule.onNodeWithText("That change could not be saved.").assertIsDisplayed()
        rule.onNodeWithText("Door lock fault").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun cancelOnTheEditDialogSavesNothing() {
        val saved = showEdit(EditTarget.Code("c1", "E-21", "Door fault", ""))

        field("Code").performTextInput("X")
        rule.onNodeWithText("Cancel").performClick()

        assertTrue(saved.targets.isEmpty())
        assertEquals(1, saved.dismissed)
    }

    // ---- What the import flow tells the person ---------------------------------------------

    private fun showNotice(
        notice: ImportNotice,
        opened: MutableList<String> = mutableListOf(),
        closed: MutableList<Unit> = mutableListOf(),
    ) {
        rule.setContent {
            ElevManTheme {
                ImportNoticeView(
                    notice = notice,
                    onOpenInLibrary = { opened += it },
                    onClose = { closed += Unit },
                )
            }
        }
    }

    @Test
    fun theDuplicateMessageNamesTheExistingTitle() {
        val notice = ImportResult.Duplicate("manual-1", "Alpha 100 service manual").toNotice(modelId = "model-1")!!

        showNotice(notice)

        rule.onNodeWithText("Already in your library as Alpha 100 service manual.").assertIsDisplayed()
    }

    @Test
    fun theDuplicateMessageOffersToOpenTheExistingManual() {
        val opened = mutableListOf<String>()
        val notice = ImportResult.Duplicate("manual-1", "Alpha 100 service manual").toNotice(modelId = "model-1")!!

        showNotice(notice, opened)
        rule.onNodeWithText("Open in library").performClick()

        assertEquals(listOf("model-1"), opened)
    }

    @Test
    fun noOpenActionWhenTheModelOfTheManualIsNotKnown() {
        val notice = ImportResult.Duplicate("manual-1", "Alpha 100 service manual").toNotice(modelId = null)!!

        showNotice(notice)

        rule.onNodeWithText("Already in your library as Alpha 100 service manual.").assertIsDisplayed()
        rule.onNodeWithText("Open in library").assertDoesNotExist()
    }

    @Test
    fun theNoTextMessageMatchesExactly() {
        showNotice(ImportResult.NoText("manual-1").toNotice(modelId = "model-1")!!)

        rule.onNodeWithText("No text found in this PDF. Enter codes by hand.").assertIsDisplayed()
    }

    @Test
    fun theUnreadableMessageMatchesExactly() {
        showNotice(ImportResult.Unreadable("manual-1", "bad xref").toNotice(modelId = "model-1")!!)

        rule.onNodeWithText("This PDF could not be read.").assertIsDisplayed()
    }

    @Test
    fun theUnreadableMessageDoesNotShowTheTechnicalReason() {
        showNotice(ImportResult.Unreadable("manual-1", "bad xref").toNotice(modelId = "model-1")!!)

        rule.onNodeWithText("bad xref", substring = true).assertDoesNotExist()
    }

    @Test
    fun anImportedManualGoesToReviewInsteadOfShowingAMessage() {
        assertNull(ImportResult.Imported("manual-1", suggestedCodes = 3, suggestedTasks = 1).toNotice(modelId = "model-1"))
    }

    @Test
    fun closingTheNoticeReportsIt() {
        val closed = mutableListOf<Unit>()
        showNotice(ImportResult.NoText("manual-1").toNotice(modelId = "model-1")!!, closed = closed)

        rule.onNodeWithText("Close").performClick()

        assertEquals(1, closed.size)
    }
}
