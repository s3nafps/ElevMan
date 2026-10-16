package com.elevman.ui.elevators

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.data.ElevManDatabase
import com.elevman.data.ElevatorModelEntity
import com.elevman.data.ElevatorRepository
import com.elevman.data.MaintenanceTaskEntity
import com.elevman.data.ManufacturerEntity
import com.elevman.data.ReviewStatus
import com.elevman.ui.HoldNextIo
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
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
import org.robolectric.Shadows.shadowOf

/** Runs the view model on real threads against an in-memory database; every wait has a timeout. */
@RunWith(AndroidJUnit4::class)
class ElevatorViewModelTest {

    private lateinit var db: ElevManDatabase
    private lateinit var scope: CoroutineScope

    private val today = LocalDate.of(2026, 10, 16)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // No allowMainThreadQueries: a blocking query on the main thread would fail.
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java).build()
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
        io: CoroutineDispatcher = Dispatchers.IO,
    ) = ElevatorViewModel(
        db = db,
        repository = ElevatorRepository(db, clockOn(repositoryDate)),
        clock = clockOn(viewModelDate),
        scope = scope,
        io = io,
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
    fun assigningATaskTheElevatorAlreadyHasIsRefusedAndChangesNothing() {
        seedElevator("e1", "Port tower")
        val viewModel = viewModel()
        openDetail(viewModel, "e1")
        assertTrue(viewModel.assignTask("t-brake"))
        viewModel.settled { it.detail?.tasks?.size == 1 && !it.busy }

        // The repository would keep one row anyway (Task 11); the view model does not even ask.
        assertFalse(viewModel.assignTask("t-brake"))

        assertEquals(TASK_NOT_AVAILABLE_MESSAGE, viewModel.state.value.message)
        assertEquals(1, runBlocking { db.elevatorTaskDao().getAll() }.size)
    }

    @Test
    fun aTaskThatIsNotInTheAssignableListIsRefusedWithAMessage() {
        seedElevator("e1", "Port tower")
        val viewModel = viewModel()
        openDetail(viewModel, "e1")

        // Another model's task, a suggestion nobody confirmed, and an id that does not exist.
        for (id in listOf("t-alpha", "t-suggested", "no-such-task")) {
            viewModel.dismissMessage()
            assertFalse(id, viewModel.assignTask(id))
            assertEquals(id, TASK_NOT_AVAILABLE_MESSAGE, viewModel.state.value.message)
        }

        assertEquals(emptyList<Any>(), runBlocking { db.elevatorTaskDao().getAll() })
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun aChangeWhileAnotherIsBeingSavedIsRefusedWithAMessage() {
        seedElevator("e1", "Port tower")
        val io = HoldNextIo()
        val viewModel = viewModel(io = io)
        openDetail(viewModel, "e1")

        // The first save is accepted and kept waiting on the IO side.
        io.holdNext()
        assertTrue(viewModel.assignTask("t-brake"))
        io.awaitHeld()
        assertTrue(viewModel.state.value.busy)

        // A second tap in the meantime is not dropped silently.
        assertFalse(viewModel.assignTask("t-rails"))
        assertEquals(BUSY_MESSAGE, viewModel.state.value.message)

        io.releaseHeld()
        val detail = viewModel.settled { it.detail?.tasks?.size == 1 && !it.busy }.detail!!
        assertEquals(listOf("Check brake"), detail.tasks.map { it.taskName })
        assertEquals(listOf("t-brake"), runBlocking { db.elevatorTaskDao().getAll() }.map { it.maintenanceTaskId })
    }

    @Test
    fun markingDoneAndAddingANoteAreRefusedWhileBusyToo() {
        seedElevator("e1", "Port tower")
        val io = HoldNextIo()
        val viewModel = viewModel(io = io)
        openDetail(viewModel, "e1")
        viewModel.assignTask("t-brake")
        val taskId = viewModel.settled { it.detail?.tasks?.size == 1 && !it.busy }.detail!!.tasks.single().elevatorTaskId
        io.holdNext()
        assertTrue(viewModel.addNote(today, "first"))
        io.awaitHeld()

        assertFalse(viewModel.markDone(taskId, today))
        assertEquals(BUSY_MESSAGE, viewModel.state.value.message)
        viewModel.dismissMessage()
        assertFalse(viewModel.addNote(today, "second"))
        assertEquals(BUSY_MESSAGE, viewModel.state.value.message)

        io.releaseHeld()
        viewModel.settled { it.detail?.log?.size == 1 && !it.busy }
        assertEquals(listOf("first"), runBlocking { db.logEntryDao().getAll() }.map { it.note })
    }

    @Test
    fun aRefusedFutureDateReturnsFalse() {
        seedElevator("e1", "Port tower")
        val viewModel = viewModel()
        openDetail(viewModel, "e1")

        assertFalse(viewModel.addNote(today.plusDays(1), "later"))
        assertTrue(viewModel.addNote(today, "now"))
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

    // ---- R18h: the list is loaded on IO, not on the main thread --------------------------------

    /** Records the thread of every instant it is asked for. */
    private class ProbeClock(private val delegate: Clock, val threads: ConcurrentLinkedQueue<Thread>) : Clock() {
        override fun getZone() = delegate.zone
        override fun withZone(zone: ZoneId): Clock = ProbeClock(delegate.withZone(zone), threads)
        override fun instant(): Instant {
            threads += Thread.currentThread()
            return delegate.instant()
        }
    }

    /** `Dispatchers.IO`, but it counts what it is given and notes the thread each block runs on. */
    private class RecordingIo : CoroutineDispatcher() {
        val dispatched = AtomicInteger()
        val threads = ConcurrentLinkedQueue<Thread>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched.incrementAndGet()
            Dispatchers.IO.dispatch(context, Runnable {
                threads += Thread.currentThread()
                block.run()
            })
        }
    }

    private fun idleMainLooperUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("the condition was not met in time", condition())
    }

    @Test
    fun theElevatorListAndDetailAreLoadedOnIoAndNotOnTheMainThread() {
        seedElevator("e1", "Port tower")
        val main = Looper.getMainLooper().thread
        val io = RecordingIo()
        val repositoryThreads = ConcurrentLinkedQueue<Thread>()
        // The scope the app uses: everything starts on the main thread, so only the view model's own
        // switch to the IO dispatcher keeps the reads off it. The database has no allowMainThreadQueries.
        val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val viewModel = ElevatorViewModel(
                db = db,
                repository = ElevatorRepository(db, ProbeClock(clockOn(today), repositoryThreads)),
                clock = clockOn(today),
                scope = mainScope,
                io = io,
            )

            // The list, loaded when the view model is made.
            idleMainLooperUntil { viewModel.state.value.loaded }
            assertEquals(listOf("Port tower"), viewModel.state.value.groups.map { it.site })
            assertTrue("the list was loaded through the IO dispatcher", io.dispatched.get() >= 1)

            // The detail, which asks the repository for the due dates and the log.
            viewModel.openElevator("e1")
            idleMainLooperUntil { viewModel.state.value.detail?.id == "e1" }
            assertTrue("the repository was asked", repositoryThreads.isNotEmpty())
            assertTrue("the repository ran off the main thread", repositoryThreads.none { it === main })
        } finally {
            mainScope.cancel()
        }
        assertTrue("every IO block ran off the main thread", io.threads.isNotEmpty() && io.threads.none { it === main })
    }
}
