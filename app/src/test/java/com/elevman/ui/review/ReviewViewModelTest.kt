package com.elevman.ui.review

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.data.ElevManDatabase
import com.elevman.data.ErrorCodeEntity
import com.elevman.data.ItemKind
import com.elevman.data.MaintenanceTaskEntity
import com.elevman.data.ReviewRepository
import com.elevman.data.ReviewStatus
import java.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the view model on real threads against an in-memory database; every wait has a timeout. */
@RunWith(AndroidJUnit4::class)
class ReviewViewModelTest {

    private lateinit var db: ElevManDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: ReviewViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runBlocking {
            db.errorCodeDao().upsertAll(
                listOf(
                    code("c-b", "E-30", page = 5),
                    code("c-a", "E-21", page = 5, causeAndFix = "Check the sensor"),
                    code("other", "E-99", page = 1, manual = "B"),
                ),
            )
            db.maintenanceTaskDao().upsertAll(listOf(task("t-1", "Lubricate rails", page = 2)))
        }
        viewModel = ReviewViewModel(
            "A", ReviewRepository(db, Clock.systemUTC()), db.errorCodeDao(), db.maintenanceTaskDao(), scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun code(id: String, code: String, page: Int, manual: String = "A", causeAndFix: String = "") =
        ErrorCodeEntity(id, "m", manual, code, code.filter { it.isLetterOrDigit() }, "meaning of $code", causeAndFix, page, ReviewStatus.SUGGESTED, 1L)

    private fun task(id: String, name: String, page: Int) =
        MaintenanceTaskEntity(id, "m", "A", name, 6, page, ReviewStatus.SUGGESTED, 1L)

    private fun settled(done: (ReviewUiState) -> Boolean): ReviewUiState =
        runBlocking { withTimeout(10.seconds) { viewModel.state.first { it.loaded && done(it) } } }

    private fun ids(state: ReviewUiState) = state.items.map { it.id }

    @Test
    fun theListIsTheRepositoryListForThisManualInItsOrder() {
        val state = settled { true }

        // By page, then label: the repository's order, which the screen must not change.
        assertEquals(listOf("t-1", "c-a", "c-b"), ids(state))
    }

    @Test
    fun acceptTakesTheItemOutOfTheList() {
        settled { true }

        viewModel.accept(settled { true }.items.first { it.id == "c-a" })
        val state = settled { "c-a" !in ids(it) }

        assertEquals(listOf("t-1", "c-b"), ids(state))
        assertEquals(ReviewStatus.CONFIRMED, runBlocking { db.errorCodeDao().findById("c-a") }!!.status)
    }

    @Test
    fun rejectTakesTheItemOutOfTheList() {
        val item = settled { true }.items.first { it.id == "t-1" }

        viewModel.reject(item)
        val state = settled { "t-1" !in ids(it) }

        assertEquals(listOf("c-a", "c-b"), ids(state))
        assertEquals(ReviewStatus.REJECTED, runBlocking { db.maintenanceTaskDao().findById("t-1") }!!.status)
    }

    @Test
    fun acceptAllConfirmsOnlyThisManual() {
        settled { true }

        viewModel.acceptAll()
        val state = settled { it.items.isEmpty() }

        assertTrue(state.items.isEmpty())
        assertEquals(ReviewStatus.SUGGESTED, runBlocking { db.errorCodeDao().findById("other") }!!.status)
    }

    @Test
    fun startingAnEditLoadsTheStoredFields() {
        val item = settled { true }.items.first { it.id == "c-a" }

        viewModel.startEdit(item)
        val state = settled { it.editing != null }

        assertEquals(EditTarget.Code("c-a", "E-21", "meaning of E-21", "Check the sensor"), state.editing)
    }

    @Test
    fun startingAnEditOfATaskLoadsNameAndInterval() {
        val item = settled { true }.items.first { it.id == "t-1" }

        viewModel.startEdit(item)
        val state = settled { it.editing != null }

        assertEquals(EditTarget.Task("t-1", "Lubricate rails", 6), state.editing)
    }

    @Test
    fun savingACodeKeepsItInTheListWithTheNewValuesAndClosesTheDialog() {
        settled { true }

        viewModel.save(EditTarget.Code("c-b", "E-10", "New meaning", "New fix"))
        val state = settled { it.items.any { item -> item.label == "E-10" } }

        assertNull(state.editing)
        // E-10 sorts before E-21 on the same page, so the repository puts it first of the two.
        assertEquals(listOf("t-1", "c-b", "c-a"), ids(state))
        val row = runBlocking { db.errorCodeDao().findById("c-b") }!!
        assertEquals("New fix", row.causeAndFix)
        assertEquals(ReviewStatus.SUGGESTED, row.status)
    }

    @Test
    fun savingATaskChangesItsNameAndInterval() {
        settled { true }

        viewModel.save(EditTarget.Task("t-1", "Inspect door rollers", 12))
        settled { it.items.any { item -> item.label == "Inspect door rollers" } }

        val row = runBlocking { db.maintenanceTaskDao().findById("t-1") }!!
        assertEquals(12, row.intervalMonths)
        assertEquals(ItemKind.MAINTENANCE_TASK, settled { true }.items.first { it.id == "t-1" }.kind)
    }

    @Test
    fun aSaveThatTheRepositoryRefusesShowsAMessageAndChangesNothing() {
        settled { true }

        viewModel.save(EditTarget.Task("t-1", "x", 0))
        val state = settled { it.message != null }

        assertEquals(CHANGE_FAILED_MESSAGE, state.message)
        assertEquals(6, runBlocking { db.maintenanceTaskDao().findById("t-1") }!!.intervalMonths)
    }

    @Test
    fun aFailedSaveKeepsTheEditOpenAndExposesTheMessageForTheDialog() {
        val item = settled { true }.items.first { it.id == "t-1" }
        viewModel.startEdit(item)
        val loaded = settled { it.editing != null }.editing

        viewModel.save(EditTarget.Task("t-1", "x", 0))
        val state = settled { it.message != null }

        // The dialog stays up, so what was typed is still there, and it can show the message.
        assertEquals(loaded, state.editing)
        assertEquals(CHANGE_FAILED_MESSAGE, state.message)
    }

    @Test
    fun cancellingAfterAFailedSaveClearsItsMessage() {
        val item = settled { true }.items.first { it.id == "t-1" }
        viewModel.startEdit(item)
        settled { it.editing != null }
        viewModel.save(EditTarget.Task("t-1", "x", 0))
        settled { it.message != null }

        viewModel.dismissEdit()

        assertNull(viewModel.state.value.editing)
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun dismissingTheEditClosesTheDialogWithoutSaving() {
        val item = settled { true }.items.first { it.id == "c-a" }
        viewModel.startEdit(item)
        settled { it.editing != null }

        viewModel.dismissEdit()

        assertNull(viewModel.state.value.editing)
        assertEquals("E-21", runBlocking { db.errorCodeDao().findById("c-a") }!!.code)
    }
}
