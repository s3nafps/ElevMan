package com.elevman.ui.library

import android.content.Context
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
import com.elevman.domain.normalizeCode
import com.elevman.ui.HoldNextIo
import com.elevman.ui.review.CHANGE_FAILED_MESSAGE
import com.elevman.ui.review.EditTarget
import java.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the view model on real threads against an in-memory database; every wait has a timeout. */
@RunWith(AndroidJUnit4::class)
class LibraryViewModelTest {

    private lateinit var db: ElevManDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun viewModel(io: CoroutineDispatcher = Dispatchers.IO) =
        LibraryViewModel(db, ReviewRepository(db, Clock.systemUTC()), scope, io)

    private fun LibraryViewModel.settled(done: (LibraryUiState) -> Boolean = { true }): LibraryUiState =
        runBlocking { withTimeout(10.seconds) { state.first { it.loaded && done(it) } } }

    private fun code(id: String, manual: String, model: String, status: ReviewStatus, code: String = "E$id") =
        ErrorCodeEntity(id, model, manual, code, normalizeCode(code), "meaning $id", "", 4, status, 1L)

    private fun task(id: String, manual: String, model: String, status: ReviewStatus, name: String = "Task $id") =
        MaintenanceTaskEntity(id, model, manual, name, 6, 9, status, 1L)

    private fun seed() = runBlocking {
        db.manufacturerDao().upsertAll(
            listOf(ManufacturerEntity("mk-s", "schindler", 1L), ManufacturerEntity("mk-o", "Otis", 1L)),
        )
        db.elevatorModelDao().upsertAll(
            listOf(
                ElevatorModelEntity("m-gen2", "mk-o", "Gen2", "", 1L),
                ElevatorModelEntity("m-alpha", "mk-o", "alpha", "", 1L),
                ElevatorModelEntity("m-3300", "mk-s", "3300", "", 1L),
                ElevatorModelEntity("m-lost", "mk-gone", "Lost", "", 1L),
            ),
        )
        db.manualDao().upsertAll(
            listOf(
                ManualEntity("man-1", "m-gen2", "Service manual", "h1", 120, 1L),
                ManualEntity("man-2", "m-gen2", "Installation manual", "h2", 80, 1L),
                ManualEntity("man-3", "m-alpha", "Alpha manual", "h3", 10, 1L),
            ),
        )
        db.errorCodeDao().upsertAll(
            listOf(
                // man-1: two codes waiting, one confirmed, one rejected.
                code("1", "man-1", "m-gen2", ReviewStatus.SUGGESTED),
                code("2", "man-1", "m-gen2", ReviewStatus.SUGGESTED),
                code("3", "man-1", "m-gen2", ReviewStatus.CONFIRMED, code = "E-21"),
                code("4", "man-1", "m-gen2", ReviewStatus.REJECTED),
                code("5", "man-3", "m-alpha", ReviewStatus.CONFIRMED),
            ),
        )
        db.maintenanceTaskDao().upsertAll(
            listOf(
                // man-2: one task waiting. man-1: a confirmed one.
                task("1", "man-2", "m-gen2", ReviewStatus.SUGGESTED),
                task("2", "man-1", "m-gen2", ReviewStatus.CONFIRMED, name = "Check brake"),
            ),
        )
    }

    @Test
    fun aModelShowsTheSuggestionsWaitingSummedOverItsManuals() {
        seed()

        val state = viewModel().settled()

        val models = state.manufacturers.flatMap { it.models }.associate { it.name to it.toReview }
        // Gen2: two codes in one manual and one task in the other. alpha and 3300: none.
        assertEquals(3, models["Gen2"])
        assertEquals(0, models["alpha"])
        assertEquals(0, models["3300"])
    }

    @Test
    fun manufacturersAndTheirModelsAreListedByName() {
        seed()

        val state = viewModel().settled()

        assertEquals(listOf("Otis", "schindler", UNKNOWN_MANUFACTURER), state.manufacturers.map { it.name })
        assertEquals(listOf("alpha", "Gen2"), state.manufacturers[0].models.map { it.name })
        assertEquals(listOf("Lost"), state.manufacturers[2].models.map { it.name })
    }

    @Test
    fun anEmptyLibraryIsLoadedAndEmpty() {
        val state = viewModel().settled()

        assertTrue(state.manufacturers.isEmpty())
        assertFalse(state.failed)
    }

    @Test
    fun aModelPageListsItsManualsAndOnlyConfirmedCodesAndTasks() {
        seed()
        val library = viewModel()
        library.settled()

        library.openModel("m-gen2")
        val state = library.settled { it.detailLoaded && it.detail != null }

        val detail = state.detail!!
        assertEquals("Gen2", detail.name)
        assertEquals("Otis", detail.manufacturerName)
        assertEquals(
            listOf(Triple("Installation manual", 80, 1), Triple("Service manual", 120, 2)),
            detail.manuals.map { Triple(it.title, it.pageCount, it.toReview) },
        )
        assertEquals(listOf("E-21"), detail.codes.map { it.code })
        assertEquals(listOf("Check brake"), detail.tasks.map { it.name })
    }

    @Test
    fun aModelThatIsGoneHasNoDetail() {
        val library = viewModel()
        library.settled()

        library.openModel("nope")
        val state = library.settled { it.detailLoaded }

        assertNull(state.detail)
    }

    @Test
    fun pagesStackAndBackGoesOneDown() {
        val library = viewModel()

        library.openModel("m-gen2")
        library.openReview("man-1")
        assertEquals(
            listOf(LibraryPage.Overview, LibraryPage.Model("m-gen2"), LibraryPage.Review("man-1")),
            library.state.value.pages,
        )
        assertTrue(library.state.value.hidesTabBar)

        library.back()
        assertEquals(LibraryPage.Model("m-gen2"), library.state.value.page)
        assertFalse(library.state.value.hidesTabBar)

        library.back()
        library.back()
        assertEquals(LibraryPage.Overview, library.state.value.page)
    }

    @Test
    fun importHidesTheTabBarAndIsOpenedOnce() {
        val library = viewModel()

        library.openImport()
        library.openImport()

        assertEquals(listOf(LibraryPage.Overview, LibraryPage.Import), library.state.value.pages)
        assertTrue(library.state.value.hidesTabBar)
    }

    @Test
    fun aFinishedImportShowsTheReviewOnTopOfTheModel() {
        val library = viewModel()
        library.openImport()

        library.showImported("m-gen2", "man-9")

        assertEquals(
            listOf(LibraryPage.Overview, LibraryPage.Model("m-gen2"), LibraryPage.Review("man-9")),
            library.state.value.pages,
        )
    }

    @Test
    fun openingTheLibraryAgainAfterAChangeShowsTheNewCount() {
        seed()
        val library = viewModel()
        library.settled()
        library.openModel("m-gen2")
        library.openReview("man-1")

        // The person accepts everything in man-1, then goes back.
        runBlocking { ReviewRepository(db, Clock.systemUTC()).acceptAll("man-1") }
        library.back()
        library.back()
        val state = library.settled { s -> s.manufacturers.flatMap { it.models }.first { it.name == "Gen2" }.toReview == 1 }

        assertEquals(1, state.manufacturers.flatMap { it.models }.first { it.name == "Gen2" }.toReview)
    }

    // ---- Entering codes and tasks by hand -----------------------------------------------------

    private fun LibraryViewModel.openModelPage(modelId: String = "m-gen2") {
        openModel(modelId)
        settled { it.detailLoaded && it.detail != null }
    }

    private fun savedCodes() = runBlocking { db.errorCodeDao().getAll() }

    private fun savedTasks() = runBlocking { db.maintenanceTaskDao().getAll() }

    @Test
    fun aSavedCodeIsListedOnTheModelPageAfterTheFormCloses() {
        seed()
        val library = viewModel()
        library.openModelPage()

        library.openCodeEntry("man-2")
        assertEquals(ManualEntry.Code("man-2", "Installation manual"), library.state.value.entry)
        library.saveCode(NewCode("e-22", "Door lock fault", "Replace the lock", 12))
        val state = library.settled { s -> s.entry == null && s.detail?.codes?.any { it.code == "e-22" } == true }

        val detail = state.detail!!
        val saved = detail.codes.single { it.code == "e-22" }
        assertEquals(ReviewStatus.CONFIRMED, saved.status)
        assertEquals("E22", saved.normalizedCode)
        assertEquals("man-2", saved.manualId)
        assertEquals("m-gen2", saved.modelId)
        assertEquals("Door lock fault", saved.meaning)
        assertEquals("Replace the lock", saved.causeAndFix)
        assertEquals(12, saved.pageNumber)
        // The existing confirmed code is still there, the list stays sorted, and nothing is left over.
        assertEquals(listOf("E-21", "e-22"), detail.codes.map { it.code })
        assertFalse(state.entrySaving)
        assertNull(state.entryError)
        // A typed code is not a suggestion: the number to review did not change.
        assertEquals(
            listOf(Triple("Installation manual", 80, 1), Triple("Service manual", 120, 2)),
            detail.manuals.map { Triple(it.title, it.pageCount, it.toReview) },
        )
    }

    @Test
    fun aSavedTaskIsListedOnTheModelPageAfterTheFormCloses() {
        seed()
        val library = viewModel()
        library.openModelPage()

        library.openTaskEntry("man-1")
        assertEquals(ManualEntry.Task("man-1", "Service manual"), library.state.value.entry)
        library.saveTask(NewTask("Inspect door rollers", 12, 0))
        val state = library.settled { s -> s.entry == null && s.detail?.tasks?.any { it.name == "Inspect door rollers" } == true }

        val detail = state.detail!!
        val saved = detail.tasks.single { it.name == "Inspect door rollers" }
        assertEquals(ReviewStatus.CONFIRMED, saved.status)
        assertEquals(12, saved.intervalMonths)
        assertEquals(0, saved.pageNumber)
        assertEquals("man-1", saved.manualId)
        assertEquals("m-gen2", saved.modelId)
        assertEquals(listOf("Check brake", "Inspect door rollers"), detail.tasks.map { it.name })
    }

    @Test
    fun aFailedSaveKeepsTheFormOpenWithItsMessageAndCanBeTriedAgain() {
        seed()
        val library = viewModel()
        library.openModelPage()
        library.openCodeEntry("man-1")
        // The manual goes away while the form is open (a backup import can do that).
        db.openHelper.writableDatabase.execSQL("DELETE FROM ManualEntity WHERE id = 'man-1'")
        val before = savedCodes().size

        library.saveCode(NewCode("e-22", "Door lock fault", "", 0))
        val failed = library.settled { it.entryError != null }

        assertEquals(ENTRY_FAILED_MESSAGE, failed.entryError)
        assertEquals(ManualEntry.Code("man-1", "Service manual"), failed.entry)
        assertFalse(failed.entrySaving)
        assertEquals(before, savedCodes().size)

        // The manual is back: the same form saves, and the message goes.
        runBlocking { db.manualDao().upsertAll(listOf(ManualEntity("man-1", "m-gen2", "Service manual", "h1", 120, 1L))) }
        library.saveCode(NewCode("e-22", "Door lock fault", "", 0))
        val saved = library.settled { it.entry == null && it.detail?.codes?.any { c -> c.code == "e-22" } == true }

        assertNull(saved.entryError)
        assertEquals(before + 1, savedCodes().size)
    }

    @Test
    fun cancellingTheFormClosesItAndClearsItsMessage() {
        seed()
        val library = viewModel()
        library.openModelPage()
        library.openTaskEntry("man-1")
        db.openHelper.writableDatabase.execSQL("DELETE FROM ManualEntity WHERE id = 'man-1'")
        library.saveTask(NewTask("Inspect door rollers", 12, 0))
        library.settled { it.entryError != null }

        library.dismissEntry()

        assertNull(library.state.value.entry)
        assertNull(library.state.value.entryError)
        assertEquals(1, savedTasks().count { it.manualId == "man-1" })
    }

    @Test
    fun theWriteRunsOnTheIoDispatcherAndASecondSaveWhileItRunsIsIgnored() {
        seed()
        val io = HoldNextIo()
        val library = viewModel(io)
        library.openModelPage()
        library.openCodeEntry("man-1")
        val before = savedCodes().size

        io.holdNext()
        library.saveCode(NewCode("e-22", "Door lock fault", "", 0))
        io.awaitHeld()
        // Nothing was written on the calling thread: the write waits on the io dispatcher.
        assertEquals(before, savedCodes().size)
        assertTrue(library.state.value.entrySaving)

        // A second tap on Save meanwhile does not write a second row.
        library.saveCode(NewCode("e-22", "Door lock fault", "", 0))
        io.releaseHeld()
        library.settled { it.entry == null && !it.entrySaving }

        assertEquals(before + 1, savedCodes().size)
        assertEquals(1, savedCodes().count { it.code == "e-22" })
    }

    @Test
    fun aFormCannotBeOpenedForAManualThatIsNotOnThePage() {
        seed()
        val library = viewModel()
        library.openModelPage()

        library.openCodeEntry("man-3")
        assertNull(library.state.value.entry)
        library.openTaskEntry("nope")
        assertNull(library.state.value.entry)
        // Saving with no form open writes nothing.
        library.saveCode(NewCode("e-22", "Door lock fault", "", 0))
        assertFalse(library.state.value.entrySaving)
        assertEquals(5, savedCodes().size)
    }

    @Test
    fun leavingTheModelPageClosesTheForm() {
        seed()
        val library = viewModel()
        library.openModelPage()
        library.openCodeEntry("man-1")

        library.back()

        assertNull(library.state.value.entry)
        assertNull(library.state.value.entryError)
    }

    // ---- Correcting a listed code or task -----------------------------------------------------

    @Test
    fun anEditedHandEnteredCodeKeepsStatusConfirmedAndShowsItsNewText() {
        seed()
        val library = viewModel()
        library.openModelPage()
        library.openCodeEntry("man-2")
        library.saveCode(NewCode("e-22", "Door lok fault", "", 12))
        val listed = library.settled { s -> s.entry == null && s.detail?.codes?.any { it.code == "e-22" } == true }
        val id = listed.detail!!.codes.single { it.code == "e-22" }.id

        library.startEditCode(id)
        assertEquals(EditTarget.Code(id, "e-22", "Door lok fault", ""), library.state.value.editing)
        library.saveEdit(EditTarget.Code(id, "E-22", "Door lock fault", "Replace the lock"))
        val state = library.settled { s -> s.editing == null && s.detail?.codes?.any { it.meaning == "Door lock fault" } == true }

        val stored = runBlocking { db.errorCodeDao().findById(id)!! }
        assertEquals(ReviewStatus.CONFIRMED, stored.status)
        assertEquals("E-22", stored.code)
        assertEquals("E22", stored.normalizedCode)
        assertEquals("Door lock fault", stored.meaning)
        assertEquals("Replace the lock", stored.causeAndFix)
        // Edited in place: same manual, same page, and no second row.
        assertEquals("man-2", stored.manualId)
        assertEquals(12, stored.pageNumber)
        assertEquals(1, savedCodes().count { it.normalizedCode == "E22" })
        val shown = state.detail!!.codes.single { it.id == id }
        assertEquals("Door lock fault", shown.meaning)
        assertFalse(state.entrySaving)
        assertNull(state.entryError)
    }

    @Test
    fun anEditedTaskKeepsItsStatusAndShowsItsNewInterval() {
        seed()
        val library = viewModel()
        library.openModelPage()

        library.startEditTask("2")
        assertEquals(EditTarget.Task("2", "Check brake", 6), library.state.value.editing)
        library.saveEdit(EditTarget.Task("2", "Check the brake", 12))
        val state = library.settled { s -> s.editing == null && s.detail?.tasks?.any { it.name == "Check the brake" } == true }

        val stored = state.detail!!.tasks.single { it.id == "2" }
        assertEquals(12, stored.intervalMonths)
        assertEquals(ReviewStatus.CONFIRMED, stored.status)
        assertEquals(1, savedTasks().count { it.manualId == "man-1" })
    }

    @Test
    fun aFailedEditKeepsTheDialogOpenWithTheReviewsMessage() {
        seed()
        val library = viewModel()
        library.openModelPage()
        library.startEditTask("2")

        // The repository refuses an interval below 1 (the dialog would not even ask).
        library.saveEdit(EditTarget.Task("2", "Check the brake", 0))
        val failed = library.settled { it.entryError != null }

        assertEquals(CHANGE_FAILED_MESSAGE, failed.entryError)
        assertEquals(EditTarget.Task("2", "Check brake", 6), failed.editing)
        assertFalse(failed.entrySaving)
        assertEquals("Check brake", savedTasks().single { it.id == "2" }.name)

        // Cancel closes it and clears the message; trying again with good values saves.
        library.startEditTask("2")
        library.saveEdit(EditTarget.Task("2", "Check the brake", 3))
        val saved = library.settled { it.editing == null && it.detail?.tasks?.any { t -> t.name == "Check the brake" } == true }
        assertNull(saved.entryError)
        library.dismissEntry()
        assertNull(library.state.value.editing)
    }

    @Test
    fun anEditCannotBeStartedForAnItemThatIsNotListed() {
        seed()
        val library = viewModel()
        library.openModelPage()

        // "1" is a suggestion (not listed here), "5" belongs to another model, "nope" does not exist.
        for (id in listOf("1", "5", "nope")) {
            library.startEditCode(id)
            assertNull(id, library.state.value.editing)
        }
        library.startEditTask("1")
        assertNull(library.state.value.editing)
        // Saving with no edit open writes nothing.
        library.saveEdit(EditTarget.Code("3", "X", "changed", ""))
        assertFalse(library.state.value.entrySaving)
        assertEquals("meaning 3", savedCodes().single { it.id == "3" }.meaning)
    }

    @Test
    fun leavingTheModelPageClosesAnOpenEdit() {
        seed()
        val library = viewModel()
        library.openModelPage()
        library.startEditCode("3")

        library.back()

        assertNull(library.state.value.editing)
        assertNull(library.state.value.entryError)
    }
}
