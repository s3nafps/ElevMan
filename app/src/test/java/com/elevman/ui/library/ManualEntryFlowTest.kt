package com.elevman.ui.library

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.data.ElevManDatabase
import com.elevman.data.ElevatorModelEntity
import com.elevman.data.ErrorCodeEntity
import com.elevman.data.MaintenanceTaskEntity
import com.elevman.data.ManualEntity
import com.elevman.data.ManufacturerEntity
import com.elevman.data.ReviewRepository
import com.elevman.data.ReviewStatus
import com.elevman.ui.review.CHANGE_FAILED_MESSAGE
import com.elevman.ui.review.EditTarget
import com.elevman.ui.theme.ElevManTheme
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The model page with its entry forms, the real view model and a real in-memory database: what the
 * person sees and what ends up stored. Default screen size: a dialog with text fields never goes idle
 * on a tall one.
 */
@RunWith(AndroidJUnit4::class)
class ManualEntryFlowTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: ElevManDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var library: LibraryViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runBlocking {
            db.manufacturerDao().upsertAll(listOf(ManufacturerEntity("mk-o", "Otis", 1L)))
            db.elevatorModelDao().upsertAll(listOf(ElevatorModelEntity("m-gen2", "mk-o", "Gen2", "", 1L)))
            db.manualDao().upsertAll(listOf(ManualEntity("man-1", "m-gen2", "Service manual", "h1", 120, 1L)))
        }
        library = LibraryViewModel(db, ReviewRepository(db, Clock.systemUTC()), scope)
        library.openModel("m-gen2")
        rule.setContent {
            val state by library.state.collectAsState()
            ElevManTheme {
                ModelPage(modelId = "m-gen2", state = state, library = library, onOpenManual = { _, _ -> })
            }
        }
        waitFor("Add code")
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    /** The view model works on other threads, so what the screen shows catches up a moment later. */
    private fun waitFor(text: String) = rule.waitUntil(timeoutMillis = 10_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitUntilGone(text: String) = rule.waitUntil(timeoutMillis = 10_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }

    private fun storedCodes() = runBlocking { db.errorCodeDao().getAll() }

    private fun storedTasks() = runBlocking { db.maintenanceTaskDao().getAll() }

    @Test
    fun addCodeOpensTheFormForThatManual() {
        rule.onNodeWithText("Add code").performClick()

        rule.onNodeWithText("Add error code").assertIsDisplayed()
        // The manual line of the form (the model page behind it lists the manual too).
        rule.onNodeWithText("Manual: Service manual").assertIsDisplayed()
        field("Code").assertIsDisplayed()
    }

    @Test
    fun addTaskOpensTheFormForThatManual() {
        rule.onNodeWithText("Add task").performClick()

        rule.onNodeWithText("Add maintenance task").assertIsDisplayed()
        rule.onNodeWithText("Manual: Service manual").assertIsDisplayed()
        field("Interval (months)").assertIsDisplayed()
    }

    @Test
    fun aBlankCodeAndMeaningAreRejectedAndNothingIsSaved() {
        rule.onNodeWithText("Add code").performClick()

        rule.onNodeWithText("Save").performClick()

        rule.onNodeWithText("Enter the code.").assertIsDisplayed()
        rule.onNodeWithText("Enter the meaning.").assertIsDisplayed()
        // The form is still open, and the view model never started a save.
        rule.onNodeWithText("Save").assertIsDisplayed()
        assertTrue(storedCodes().isEmpty())
        assertEquals(false, library.state.value.entrySaving)
        assertEquals(ManualEntry.Code("man-1", "Service manual"), library.state.value.entry)
    }

    @Test
    fun anIntervalOfZeroIsRejectedWithTheExactMessageAndNothingIsSaved() {
        rule.onNodeWithText("Add task").performClick()
        field("Name").performTextInput("Check brake")
        field("Interval (months)").performTextInput("0")

        rule.onNodeWithText("Save").performClick()

        rule.onNodeWithText("Interval must be at least 1 month.").assertIsDisplayed()
        assertTrue(storedTasks().isEmpty())
        assertEquals(false, library.state.value.entrySaving)
    }

    @Test
    fun aFailedSaveKeepsTheFormOpenWithTheTypedCodeStillShown() {
        rule.onNodeWithText("Add code").performClick()
        field("Code").performTextInput("E-22")
        field("Meaning").performTextInput("Door lock fault")
        // The manual goes away while the form is open (a backup import can do that).
        db.openHelper.writableDatabase.execSQL("DELETE FROM ManualEntity WHERE id = 'man-1'")

        rule.onNodeWithText("Save").performClick()
        waitFor(ENTRY_FAILED_MESSAGE)

        rule.onNodeWithText(ENTRY_FAILED_MESSAGE).assertIsDisplayed()
        rule.onNodeWithText("E-22").assertIsDisplayed()
        rule.onNodeWithText("Door lock fault").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed()
        assertTrue(storedCodes().isEmpty())
    }

    @Test
    fun aSavedCodeAppearsInTheConfirmedListAndTheFormCloses() {
        rule.onNodeWithText("Add code").performClick()
        field("Code").performTextInput("e-22")
        field("Meaning").performTextInput("Door lock fault")
        field("Cause and fix").performTextInput("Replace the lock")
        field("Page (optional)").performTextInput("12")

        rule.onNodeWithText("Save").performClick()
        waitUntilGone("Save")
        waitFor("Door lock fault")

        // The model page was read again: the new code is in its list, with its page.
        rule.onNodeWithText("e-22").assertIsDisplayed()
        rule.onNodeWithText("Page 12").assertIsDisplayed()
        rule.onNodeWithText("Replace the lock").assertIsDisplayed()
        rule.onNodeWithText("No confirmed error codes yet.").assertDoesNotExist()
        val stored = storedCodes().single()
        assertEquals(ReviewStatus.CONFIRMED, stored.status)
        assertEquals("E22", stored.normalizedCode)
        assertEquals("man-1", stored.manualId)
    }

    @Test
    fun aSavedTaskAppearsInTheConfirmedListAndTheFormCloses() {
        rule.onNodeWithText("Add task").performClick()
        field("Name").performTextInput("Inspect door rollers")
        field("Interval (months)").performTextInput("12")

        rule.onNodeWithText("Save").performClick()
        waitUntilGone("Save")
        // The tasks are the last section of the page, below what a small screen shows: scroll to it.
        rule.waitUntil(timeoutMillis = 10_000) {
            library.state.value.detail?.tasks?.any { it.name == "Inspect door rollers" } == true
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Inspect door rollers"))

        rule.onNodeWithText("Inspect door rollers").assertIsDisplayed()
        rule.onNodeWithText("Every 12 months").assertIsDisplayed()
        rule.onNodeWithText("No confirmed maintenance tasks yet.").assertDoesNotExist()
        val stored = storedTasks().single()
        assertEquals(ReviewStatus.CONFIRMED, stored.status)
        assertEquals(12, stored.intervalMonths)
        assertEquals(0, stored.pageNumber)
    }

    // ---- Correcting what was entered ----------------------------------------------------------

    @Test
    fun aCodeTypedInWithATypoCanBeEditedFromTheModelPage() {
        rule.onNodeWithText("Add code").performClick()
        field("Code").performTextInput("e-22")
        field("Meaning").performTextInput("Door lok fault")
        rule.onNodeWithText("Save").performClick()
        waitUntilGone("Save")
        waitFor("Door lok fault")

        rule.onNodeWithContentDescription("Edit error code e-22").performClick()
        // The review's edit dialog, filled with what was saved.
        rule.onNodeWithText("Edit error code").assertIsDisplayed()
        field("Meaning").performTextClearance()
        field("Meaning").performTextInput("Door lock fault")
        rule.onNodeWithText("Save").performClick()
        waitUntilGone("Save")
        waitFor("Door lock fault")

        rule.onNodeWithText("Door lok fault").assertDoesNotExist()
        val stored = storedCodes().single()
        assertEquals(ReviewStatus.CONFIRMED, stored.status)
        assertEquals("Door lock fault", stored.meaning)
        assertEquals("E22", stored.normalizedCode)
    }

    @Test
    fun anEditedCodeThatNoSearchCouldFindIsRefusedAndNothingIsSaved() {
        runBlocking {
            db.errorCodeDao().upsertAll(
                listOf(ErrorCodeEntity("c1", "m-gen2", "man-1", "E-21", "E21", "Door zone fault", "", 33, ReviewStatus.CONFIRMED, 1L)),
            )
        }
        library.reload()
        waitFor("Door zone fault")

        rule.onNodeWithContentDescription("Edit error code E-21").performClick()
        field("Code").performTextClearance()
        field("Code").performTextInput("-")
        rule.onNodeWithText("Save").performClick()

        rule.onNodeWithText("Enter a code with letters or numbers.").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed()
        // The view model never started a save, and the row is as it was.
        assertEquals(false, library.state.value.entrySaving)
        assertEquals(EditTarget.Code("c1", "E-21", "Door zone fault", ""), library.state.value.editing)
        val stored = storedCodes().single()
        assertEquals("E-21", stored.code)
        assertEquals("E21", stored.normalizedCode)
    }

    @Test
    fun aFailedEditKeepsTheDialogOpenWithWhatWasTypedAndItsMessage() {
        runBlocking {
            db.errorCodeDao().upsertAll(
                listOf(ErrorCodeEntity("c1", "m-gen2", "man-1", "E-21", "E21", "Door zone fault", "", 33, ReviewStatus.CONFIRMED, 1L)),
            )
        }
        library.reload()
        waitFor("Door zone fault")
        // From now on the database refuses to change a code.
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER no_insert BEFORE INSERT ON ErrorCodeEntity BEGIN SELECT RAISE(ABORT, 'blocked'); END")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER no_update BEFORE UPDATE ON ErrorCodeEntity BEGIN SELECT RAISE(ABORT, 'blocked'); END")

        rule.onNodeWithContentDescription("Edit error code E-21").performClick()
        field("Meaning").performTextClearance()
        field("Meaning").performTextInput("Door lock fault")
        rule.onNodeWithText("Save").performClick()
        waitFor(CHANGE_FAILED_MESSAGE)

        rule.onNodeWithText(CHANGE_FAILED_MESSAGE).assertIsDisplayed()
        rule.onNodeWithText("Door lock fault").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsDisplayed()
        assertEquals("Door zone fault", storedCodes().single().meaning)
    }

    @Test
    fun aTaskCanBeEditedFromTheModelPage() {
        runBlocking {
            db.maintenanceTaskDao().upsertAll(
                listOf(MaintenanceTaskEntity("t1", "m-gen2", "man-1", "Check brake", 6, 0, ReviewStatus.CONFIRMED, 1L)),
            )
        }
        library.reload()
        waitFor("Add code")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Check brake"))

        rule.onNodeWithContentDescription("Edit maintenance task Check brake").performClick()
        field("Interval (months)").performTextClearance()
        field("Interval (months)").performTextInput("12")
        rule.onNodeWithText("Save").performClick()
        waitUntilGone("Save")
        rule.waitUntil(timeoutMillis = 10_000) { library.state.value.detail?.tasks?.any { it.intervalMonths == 12 } == true }

        val stored = storedTasks().single()
        assertEquals(12, stored.intervalMonths)
        assertEquals(ReviewStatus.CONFIRMED, stored.status)
    }
}
