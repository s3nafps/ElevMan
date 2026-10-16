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

    private fun viewModel() = LibraryViewModel(db, ReviewRepository(db, Clock.systemUTC()), scope)

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
}
