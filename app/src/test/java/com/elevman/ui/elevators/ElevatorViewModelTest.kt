package com.elevman.ui.elevators

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.data.ElevManDatabase
import com.elevman.data.ElevatorModelEntity
import com.elevman.data.ElevatorRepository
import com.elevman.data.MaintenanceTaskEntity
import com.elevman.data.ManufacturerEntity
import com.elevman.data.ReviewStatus
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
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
class ElevatorViewModelTest {

    private lateinit var db: ElevManDatabase
    private lateinit var scope: CoroutineScope

    private val today = LocalDate.of(2026, 10, 16)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runBlocking {
            db.manufacturerDao().upsertAll(listOf(ManufacturerEntity("mk-1", "Otis", 1L)))
            db.elevatorModelDao().upsertAll(
                listOf(
                    ElevatorModelEntity("m-gen2", "mk-1", "Gen2", "", 1L),
                    ElevatorModelEntity("m-alpha", "mk-1", "Alpha", "", 1L),
                ),
            )
            db.maintenanceTaskDao().upsertAll(
                listOf(
                    task("t-brake", "m-gen2", "Check brake", 6, ReviewStatus.CONFIRMED),
                    task("t-rails", "m-gen2", "Oil guide rails", 12, ReviewStatus.CONFIRMED),
                    task("t-suggested", "m-gen2", "Suggested only", 3, ReviewStatus.SUGGESTED),
                    task("t-alpha", "m-alpha", "Alpha task", 1, ReviewStatus.CONFIRMED),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun task(id: String, model: String, name: String, months: Int, status: ReviewStatus) =
        MaintenanceTaskEntity(id, model, "manual-1", name, months, 1, status, 1L)

    private fun clockOn(date: LocalDate): Clock =
        Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)

    private fun viewModel(
        repositoryDate: LocalDate = today,
        viewModelDate: LocalDate = today,
    ) = ElevatorViewModel(
        db = db,
        repository = ElevatorRepository(db, clockOn(repositoryDate)),
        clock = clockOn(viewModelDate),
        scope = scope,
    )

    private fun ElevatorViewModel.settled(done: (ElevatorUiState) -> Boolean = { true }): ElevatorUiState =
        runBlocking { withTimeout(10.seconds) { state.first { it.loaded && done(it) } } }

    private fun seedElevator(id: String, site: String, model: String = "m-gen2", serial: String = "SN-$id") = runBlocking {
        db.elevatorDao().upsertAll(
            listOf(
                com.elevman.data.ElevatorEntity(id, site, "Customer $id", model, serial, "notes $id", 1L),
            ),
        )
    }

    // ---- The list -----------------------------------------------------------------------------

    @Test
    fun elevatorsAreGroupedBySiteAndTheSitesAreSorted() {
        seedElevator("e1", "Port tower", serial = "B-2")
        seedElevator("e2", "harbour office", serial = "A-1")
        seedElevator("e3", "Port tower", model = "m-alpha", serial = "A-9")
        seedElevator("e4", "   ", serial = "Z-0")
        val viewModel = viewModel()

        val state = viewModel.settled { it.groups.isNotEmpty() }

        assertEquals(listOf("harbour office", "Port tower", NO_SITE), state.groups.map { it.site })
        val port = state.groups.single { it.site == "Port tower" }
        // Models read as "Manufacturer Model"; elevators within a site by model, then serial number.
        assertEquals(listOf("e3", "e1"), port.elevators.map { it.id })
        assertEquals(listOf("Otis Alpha", "Otis Gen2"), port.elevators.map { it.modelName })
        assertEquals(listOf("A-9", "B-2"), port.elevators.map { it.serialNumber })
    }

    @Test
    fun theModelChoicesAreTheExistingModelsWithTheirManufacturer() {
        val viewModel = viewModel()

        val state = viewModel.settled()

        assertEquals(listOf("Otis Alpha", "Otis Gen2"), state.models.map { it.label })
        assertEquals(listOf("m-alpha", "m-gen2"), state.models.map { it.id })
    }

    @Test
    fun anEmptyDatabaseIsLoadedWithNoGroups() {
        val state = viewModel().settled()

        assertTrue(state.loaded)
        assertFalse(state.failed)
        assertEquals(emptyList<SiteGroup>(), state.groups)
    }

    // ---- Add elevator ---------------------------------------------------------------------------

    @Test
    fun addingAnElevatorSavesItAndGoesBackToTheList() {
        val viewModel = viewModel()
        viewModel.settled()
        viewModel.openAddElevator()
        assertEquals(ElevatorPage.AddElevator, viewModel.state.value.page)

        viewModel.addElevator(
            site = " Harbour office ",
            customer = "Acme",
            modelId = "m-gen2",
            serialNumber = "SN-77",
            notes = "Machine room on the roof",
        )
        val state = viewModel.settled { it.groups.isNotEmpty() && it.page == ElevatorPage.Overview }

        val saved = runBlocking { db.elevatorDao().getAll() }.single()
        assertEquals("Harbour office", saved.site)
        assertEquals("Acme", saved.customer)
        assertEquals("m-gen2", saved.modelId)
        assertEquals("SN-77", saved.serialNumber)
        assertEquals("Machine room on the roof", saved.notes)
        assertEquals(listOf("Harbour office"), state.groups.map { it.site })
        assertNull(state.message)
    }

    // ---- The detail -----------------------------------------------------------------------------

    private fun openDetail(viewModel: ElevatorViewModel, id: String): ElevatorDetail {
        viewModel.settled()
        viewModel.openElevator(id)
        return viewModel.settled { it.detailLoaded && it.detail?.id == id }.detail!!
    }

    @Test
    fun theDetailHasTheModelSerialNumberAndAnEmptyLog() {
        seedElevator("e1", "Port tower")
        val viewModel = viewModel()

        val detail = openDetail(viewModel, "e1")

        assertEquals("Otis Gen2", detail.modelName)
        assertEquals("SN-e1", detail.serialNumber)
        assertEquals("Port tower", detail.site)
        assertEquals(emptyList<LogLine>(), detail.log)
        assertEquals(emptyList<Any>(), detail.tasks)
    }

    @Test
    fun onlyConfirmedTasksOfTheElevatorsModelCanBeAssigned() {
        seedElevator("e1", "Port tower")
        val viewModel = viewModel()

        val detail = openDetail(viewModel, "e1")

        assertEquals(listOf("t-brake", "t-rails"), detail.assignable.map { it.id })
    }

    @Test
    fun assigningATaskAddsItWithADueDateAndRemovesItFromTheChoices() {
        seedElevator("e1", "Port tower")
        val viewModel = viewModel()
        openDetail(viewModel, "e1")

        viewModel.assignTask("t-brake")
        val detail = viewModel.settled { it.detail?.tasks?.isNotEmpty() == true }.detail!!

        val item = detail.tasks.single()
        assertEquals("Check brake", item.taskName)
        // First due one interval (6 months) after the day it was assigned.
        assertEquals(today.plusMonths(6), item.due)
        assertFalse(item.overdue)
        assertEquals(listOf("t-rails"), detail.assignable.map { it.id })
    }

    @Test
    fun assigningTheSameTaskTwiceKeepsOneRow() {
        seedElevator("e1", "Port tower")
        val viewModel = viewModel()
        openDetail(viewModel, "e1")

        viewModel.assignTask("t-brake")
        viewModel.settled { it.detail?.tasks?.size == 1 }
        viewModel.assignTask("t-brake")
        viewModel.settled { it.detail?.tasks?.size == 1 && !it.busy }

        assertEquals(1, runBlocking { db.elevatorTaskDao().getAll() }.size)
    }

    @Test
    fun markingATaskDoneWritesALogEntryAndMovesTheDueDate() {
        seedElevator("e1", "Port tower")
        val viewModel = viewModel()
        openDetail(viewModel, "e1")
        viewModel.assignTask("t-brake")
        val taskId = viewModel.settled { it.detail?.tasks?.size == 1 }.detail!!.tasks.single().elevatorTaskId
        val doneOn = today.minusDays(3)

        viewModel.markDone(taskId, doneOn)
        val detail = viewModel.settled { it.detail?.log?.isNotEmpty() == true }.detail!!

        assertEquals(doneOn.plusMonths(6), detail.tasks.single().due)
        val line = detail.log.single()
        assertEquals(doneOn, line.date)
        assertEquals("Check brake", line.taskName)
    }

    @Test
    fun markingATaskDoneOnTodayIsAllowed() {
        seedElevator("e1", "Port tower")
        val viewModel = viewModel()
        openDetail(viewModel, "e1")
        viewModel.assignTask("t-brake")
        val taskId = viewModel.settled { it.detail?.tasks?.size == 1 }.detail!!.tasks.single().elevatorTaskId

        viewModel.markDone(taskId, today)

        assertEquals(today, viewModel.settled { it.detail?.log?.isNotEmpty() == true }.detail!!.log.single().date)
    }

    @Test
    fun aFutureDateNeverReachesTheRepository() {
        seedElevator("e1", "Port tower")
        // The repository's own clock is two days ahead, so it would accept tomorrow: only the view
        // model's guard can be what stops this date.
        val viewModel = viewModel(repositoryDate = today.plusDays(2))
        openDetail(viewModel, "e1")
        viewModel.assignTask("t-brake")
        val taskId = viewModel.settled { it.detail?.tasks?.size == 1 }.detail!!.tasks.single().elevatorTaskId

        viewModel.markDone(taskId, today.plusDays(1))
        val state = viewModel.settled { it.message != null }

        assertEquals(FUTURE_DATE_MESSAGE, state.message)
        assertEquals(emptyList<Any>(), runBlocking { db.logEntryDao().getAll() })
    }

    @Test
    fun addNoteHasTheSameGuard() {
        seedElevator("e1", "Port tower")
        val viewModel = viewModel(repositoryDate = today.plusDays(2))
        openDetail(viewModel, "e1")

        viewModel.addNote(today.plusDays(1), "Next week's visit")
        val state = viewModel.settled { it.message != null }

        assertEquals(FUTURE_DATE_MESSAGE, state.message)
        assertEquals(emptyList<Any>(), runBlocking { db.logEntryDao().getAll() })
    }

    @Test
    fun aNoteIsAddedToTheLogWithItsDate() {
        seedElevator("e1", "Port tower")
        val viewModel = viewModel()
        openDetail(viewModel, "e1")

        viewModel.addNote(today.minusDays(1), "Replaced the door roller")
        val detail = viewModel.settled { it.detail?.log?.isNotEmpty() == true }.detail!!

        val line = detail.log.single()
        assertEquals(today.minusDays(1), line.date)
        assertEquals("Replaced the door roller", line.note)
        assertNull(line.taskName)
    }

    @Test
    fun theMessageCanBeDismissed() {
        seedElevator("e1", "Port tower")
        val viewModel = viewModel()
        openDetail(viewModel, "e1")
        viewModel.addNote(today.plusDays(1), "x")
        viewModel.settled { it.message != null }

        viewModel.dismissMessage()

        assertNull(viewModel.state.value.message)
    }

    @Test
    fun backLeavesTheDetailAndTheAddForm() {
        seedElevator("e1", "Port tower")
        val viewModel = viewModel()
        openDetail(viewModel, "e1")

        viewModel.back()
        assertEquals(ElevatorPage.Overview, viewModel.state.value.page)

        viewModel.openAddElevator()
        viewModel.back()
        assertEquals(ElevatorPage.Overview, viewModel.state.value.page)

        // Back on the list does nothing.
        viewModel.back()
        assertEquals(listOf<ElevatorPage>(ElevatorPage.Overview), viewModel.state.value.pages)
    }
}
