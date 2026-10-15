package com.elevman.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElevatorRepositoryTest {

    private lateinit var db: ElevManDatabase

    private val seededAt = 1_000L
    private val today = LocalDate.of(2026, 10, 15)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun clockOn(date: LocalDate): Clock =
        Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)

    private fun repoOn(date: LocalDate = today) = ElevatorRepository(db, clockOn(date))

    private fun millisOn(date: LocalDate): Long = clockOn(date).millis()

    private suspend fun expectIllegalArgument(block: suspend () -> Unit): IllegalArgumentException {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return expected
        }
        fail("expected IllegalArgumentException")
        throw AssertionError("unreachable")
    }

    private suspend fun seedElevator(id: String = "e1") {
        db.elevatorDao().upsertAll(
            listOf(
                ElevatorEntity(
                    id = id,
                    site = "Harbour Tower",
                    customer = "Acme",
                    modelId = "model-1",
                    serialNumber = "SN-$id",
                    notes = "",
                    updatedAt = seededAt,
                ),
            ),
        )
    }

    private suspend fun seedTask(id: String, name: String, months: Int) {
        db.maintenanceTaskDao().upsertAll(
            listOf(
                MaintenanceTaskEntity(
                    id = id,
                    modelId = "model-1",
                    manualId = "manual-1",
                    name = name,
                    intervalMonths = months,
                    pageNumber = 1,
                    status = ReviewStatus.CONFIRMED,
                    updatedAt = seededAt,
                ),
            ),
        )
    }

    private suspend fun seedElevatorTask(id: String, elevatorId: String, taskId: String, start: LocalDate) {
        db.elevatorTaskDao().upsertAll(
            listOf(
                ElevatorTaskEntity(
                    id = id,
                    elevatorId = elevatorId,
                    maintenanceTaskId = taskId,
                    startDate = start,
                    updatedAt = seededAt,
                ),
            ),
        )
    }

    private suspend fun seedLog(
        id: String,
        elevatorId: String,
        taskId: String?,
        date: LocalDate,
        note: String = "",
    ) {
        db.logEntryDao().upsertAll(
            listOf(
                LogEntryEntity(
                    id = id,
                    elevatorId = elevatorId,
                    maintenanceTaskId = taskId,
                    date = date,
                    note = note,
                    updatedAt = seededAt,
                ),
            ),
        )
    }

    @Test
    fun addElevatorStoresTheRowAndReturnsItsId() = runTest {
        val repo = repoOn()

        val id = repo.addElevator("Harbour Tower", "Acme", "model-1", "SN-42", "Machine room on roof")
        val other = repo.addElevator("Harbour Tower", "Acme", "model-1", "SN-43", "")

        UUID.fromString(id)
        assertNotEquals(id, other)
        val row = db.elevatorDao().findById(id)!!
        assertEquals(
            ElevatorEntity(
                id = id,
                site = "Harbour Tower",
                customer = "Acme",
                modelId = "model-1",
                serialNumber = "SN-42",
                notes = "Machine room on roof",
                updatedAt = millisOn(today),
            ),
            row,
        )
    }

    @Test
    fun newTaskDueOneIntervalAfterAssignment() = runTest {
        val assignedOn = LocalDate.of(2026, 8, 1)
        val repo = repoOn(assignedOn)
        seedElevator("e1")
        seedTask("t-monthly", "Check brakes", months = 1)

        val elevatorTaskId = repo.assignTask("e1", "t-monthly")

        UUID.fromString(elevatorTaskId)
        val stored = db.elevatorTaskDao().getAll().single()
        assertEquals(
            ElevatorTaskEntity(
                id = elevatorTaskId,
                elevatorId = "e1",
                maintenanceTaskId = "t-monthly",
                startDate = assignedOn,
                updatedAt = millisOn(assignedOn),
            ),
            stored,
        )
        assertEquals(
            listOf(DueItem(elevatorTaskId, "Check brakes", LocalDate.of(2026, 9, 1), overdue = false)),
            repo.dueItems("e1"),
        )
        // Seen from the day it falls due, the same task is not yet overdue; a day later it is.
        assertEquals(false, repoOn(LocalDate.of(2026, 9, 1)).dueItems("e1").single().overdue)
        assertEquals(true, repoOn(LocalDate.of(2026, 9, 2)).dueItems("e1").single().overdue)
    }

    @Test
    fun assigningTheSameTaskTwiceReturnsTheExistingIdAndInsertsOneRow() = runTest {
        val firstDay = LocalDate.of(2026, 8, 1)
        val laterDay = LocalDate.of(2026, 9, 10)
        seedElevator("e1")
        seedElevator("e2")
        seedTask("t-monthly", "Check brakes", months = 1)
        seedTask("t-quarterly", "Clean pit", months = 3)

        val first = repoOn(firstDay).assignTask("e1", "t-monthly")
        val second = repoOn(laterDay).assignTask("e1", "t-monthly")

        val stored = db.elevatorTaskDao().getAll()
        assertEquals(1, stored.size)
        assertEquals(first, second)
        assertEquals(firstDay, stored.single().startDate)
        assertEquals(millisOn(firstDay), stored.single().updatedAt)
        assertEquals(
            listOf(DueItem(first, "Check brakes", LocalDate.of(2026, 9, 1), overdue = true)),
            repoOn(laterDay).dueItems("e1"),
        )

        // A different task on the same elevator, and the same task on another elevator, are new rows.
        val otherTask = repoOn(laterDay).assignTask("e1", "t-quarterly")
        val otherElevator = repoOn(laterDay).assignTask("e2", "t-monthly")

        assertEquals(3, setOf(first, otherTask, otherElevator).size)
        val all = db.elevatorTaskDao().getAll().associateBy { it.id }
        assertEquals(3, all.size)
        assertEquals(laterDay, all.getValue(otherTask).startDate)
        assertEquals(laterDay, all.getValue(otherElevator).startDate)
        assertEquals("t-quarterly", all.getValue(otherTask).maintenanceTaskId)
        assertEquals("e2", all.getValue(otherElevator).elevatorId)
    }

    @Test
    fun markDoneMovesDueForward() = runTest {
        val repo = repoOn(today)
        seedElevator("e1")
        seedTask("t-monthly", "Check brakes", months = 1)
        seedElevatorTask("et1", "e1", "t-monthly", start = LocalDate.of(2026, 8, 1))
        assertEquals(
            listOf(DueItem("et1", "Check brakes", LocalDate.of(2026, 9, 1), overdue = true)),
            repo.dueItems("e1"),
        )

        repo.markDone("et1", LocalDate.of(2026, 10, 10), "Brake pads within limits")

        assertEquals(
            listOf(DueItem("et1", "Check brakes", LocalDate.of(2026, 11, 10), overdue = false)),
            repo.dueItems("e1"),
        )
        val entry = db.logEntryDao().getAll().single()
        UUID.fromString(entry.id)
        assertEquals(
            LogEntryEntity(
                id = entry.id,
                elevatorId = "e1",
                maintenanceTaskId = "t-monthly",
                date = LocalDate.of(2026, 10, 10),
                note = "Brake pads within limits",
                updatedAt = millisOn(today),
            ),
            entry,
        )
    }

    @Test
    fun overdueListedFirst() = runTest {
        val repo = repoOn(today)
        seedElevator("e1")
        seedTask("t-monthly", "Check brakes", months = 1)
        seedTask("t-quarterly", "Clean pit", months = 3)
        seedTask("t-yearly", "Annual load test", months = 12)
        // Inserted in an order that is neither the expected one nor its reverse.
        seedElevatorTask("etC", "e1", "t-monthly", start = LocalDate.of(2026, 9, 20)) // due 2026-10-20
        seedElevatorTask("etA", "e1", "t-yearly", start = LocalDate.of(2026, 6, 1)) // due 2027-06-01
        seedElevatorTask("etD", "e1", "t-quarterly", start = LocalDate.of(2026, 5, 1)) // due 2026-08-01, overdue
        seedElevatorTask("etE", "e1", "t-monthly", start = LocalDate.of(2026, 9, 15)) // due today, not overdue
        seedElevatorTask("etB", "e1", "t-monthly", start = LocalDate.of(2026, 8, 1)) // due 2026-09-01, overdue

        val items = repo.dueItems("e1")

        assertEquals(listOf("etD", "etB", "etE", "etC", "etA"), items.map { it.elevatorTaskId })
        assertEquals(listOf(true, true, false, false, false), items.map { it.overdue })
        assertEquals(
            listOf(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 20),
                LocalDate.of(2027, 6, 1),
            ),
            items.map { it.due },
        )
        assertEquals(
            listOf("Clean pit", "Check brakes", "Check brakes", "Check brakes", "Annual load test"),
            items.map { it.taskName },
        )
    }

    @Test
    fun dueItemsTiesAreOrderedByTaskNameThenElevatorTaskId() = runTest {
        val repo = repoOn(today)
        seedElevator("e1")
        seedTask("t-alpha-1", "Alpha", months = 1)
        seedTask("t-alpha-2", "Alpha", months = 1)
        seedTask("t-beta", "Beta", months = 1)
        val start = LocalDate.of(2026, 8, 1) // all three fall due on 2026-09-01
        // Insertion order and primary-key order both differ from the expected order.
        seedElevatorTask("et-3", "e1", "t-alpha-1", start)
        seedElevatorTask("et-2", "e1", "t-beta", start)
        seedElevatorTask("et-1", "e1", "t-alpha-2", start)

        val items = repo.dueItems("e1")

        assertEquals(listOf("et-1", "et-3", "et-2"), items.map { it.elevatorTaskId })
        assertEquals(listOf("Alpha", "Alpha", "Beta"), items.map { it.taskName })
    }

    @Test
    fun dueItemsOnlyCountsLogEntriesForThisElevatorAndTask() = runTest {
        val repo = repoOn(today)
        seedElevator("e1")
        seedElevator("e2")
        seedTask("t-monthly", "Check brakes", months = 1)
        seedTask("t-other", "Clean pit", months = 1)
        seedElevatorTask("et1", "e1", "t-monthly", start = LocalDate.of(2026, 8, 1))
        seedElevatorTask("et2", "e2", "t-monthly", start = LocalDate.of(2026, 8, 1))
        // None of these may move e1's brake check.
        seedLog("other-elevator", "e2", "t-monthly", LocalDate.of(2026, 10, 12))
        seedLog("other-task", "e1", "t-other", LocalDate.of(2026, 10, 12))
        seedLog("plain-note", "e1", null, LocalDate.of(2026, 10, 14), note = "Door noisy")

        // Two completions, recorded out of date order: the later date is the one that counts.
        repo.markDone("et1", LocalDate.of(2026, 10, 10), "")
        repo.markDone("et1", LocalDate.of(2026, 9, 20), "Logged late")

        assertEquals(
            listOf(DueItem("et1", "Check brakes", LocalDate.of(2026, 11, 10), overdue = false)),
            repo.dueItems("e1"),
        )
        // e2's own completion (2026-10-12) moves e2's due date, and its list holds only its own task.
        assertEquals(
            listOf(DueItem("et2", "Check brakes", LocalDate.of(2026, 11, 12), overdue = false)),
            repo.dueItems("e2"),
        )
    }

    @Test
    fun dueItemsSkipsATaskWhoseDefinitionIsMissing() = runTest {
        val repo = repoOn(today)
        seedElevator("e1")
        seedTask("t-monthly", "Check brakes", months = 1)
        seedElevatorTask("et1", "e1", "t-monthly", start = LocalDate.of(2026, 8, 1))
        seedElevatorTask("et-dangling", "e1", "t-gone", start = LocalDate.of(2026, 8, 1))

        assertEquals(listOf("et1"), repo.dueItems("e1").map { it.elevatorTaskId })
    }

    @Test
    fun dueItemsIsEmptyForAnElevatorWithoutTasks() = runTest {
        seedElevator("e1")

        assertEquals(emptyList<DueItem>(), repoOn().dueItems("e1"))
        assertEquals(emptyList<DueItem>(), repoOn().dueItems("no-such-elevator"))
    }

    @Test
    fun futureMarkDoneRejected() = runTest {
        val repo = repoOn(today)
        seedElevator("e1")
        seedTask("t-monthly", "Check brakes", months = 1)
        seedElevatorTask("et1", "e1", "t-monthly", start = LocalDate.of(2026, 8, 1))

        expectIllegalArgument { repo.markDone("et1", LocalDate.of(2026, 10, 16), "tomorrow") }
        expectIllegalArgument { repo.markDone("et1", LocalDate.of(2027, 1, 1), "next year") }

        assertEquals(emptyList<LogEntryEntity>(), db.logEntryDao().getAll())
        assertEquals(LocalDate.of(2026, 9, 1), repo.dueItems("e1").single().due)
    }

    @Test
    fun markDoneAcceptsTodayAndBackdatedDates() = runTest {
        val repo = repoOn(today)
        seedElevator("e1")
        seedTask("t-monthly", "Check brakes", months = 1)
        seedElevatorTask("et1", "e1", "t-monthly", start = LocalDate.of(2026, 8, 1))

        repo.markDone("et1", today, "today")
        repo.markDone("et1", LocalDate.of(2026, 1, 5), "backdated")

        assertEquals(
            setOf(today, LocalDate.of(2026, 1, 5)),
            db.logEntryDao().getAll().map { it.date }.toSet(),
        )
        assertEquals(LocalDate.of(2026, 11, 15), repo.dueItems("e1").single().due)
    }

    @Test
    fun markDoneForUnknownElevatorTaskNamesTheId() = runTest {
        val repo = repoOn(today)
        seedElevator("e1")

        val error = expectIllegalArgument { repo.markDone("no-such-elevator-task", LocalDate.of(2026, 10, 10), "x") }

        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("no-such-elevator-task"))
        assertEquals(emptyList<LogEntryEntity>(), db.logEntryDao().getAll())
    }

    @Test
    fun addNoteStoresALogEntryWithoutATask() = runTest {
        val repo = repoOn(today)
        seedElevator("e1")

        repo.addNote("e1", LocalDate.of(2026, 10, 14), "Door sensor replaced")
        repo.addNote("e1", today, "Checked again")

        val entries = db.logEntryDao().getAll().sortedBy { it.date }
        assertEquals(2, entries.size)
        entries.forEach { UUID.fromString(it.id) }
        assertEquals(
            listOf(LocalDate.of(2026, 10, 14), today),
            entries.map { it.date },
        )
        assertEquals(listOf("Door sensor replaced", "Checked again"), entries.map { it.note })
        assertEquals(listOf("e1", "e1"), entries.map { it.elevatorId })
        assertNull(entries[0].maintenanceTaskId)
        assertNull(entries[1].maintenanceTaskId)
        assertEquals(listOf(millisOn(today), millisOn(today)), entries.map { it.updatedAt })
    }

    @Test
    fun addNoteRejectsAFutureDate() = runTest {
        val repo = repoOn(today)
        seedElevator("e1")

        expectIllegalArgument { repo.addNote("e1", LocalDate.of(2026, 10, 16), "typo") }

        assertEquals(emptyList<LogEntryEntity>(), db.logEntryDao().getAll())
    }

    @Test
    fun logIsNewestFirstThenById() = runTest {
        val repo = repoOn(today)
        seedElevator("e1")
        seedElevator("e2")
        seedTask("t-monthly", "Check brakes", months = 1)
        // Inserted in an order that matches neither date nor id order.
        seedLog("b", "e1", "t-monthly", LocalDate.of(2026, 9, 1), note = "mid, id b")
        seedLog("z", "e1", null, LocalDate.of(2026, 10, 12), note = "newest")
        seedLog("a", "e1", null, LocalDate.of(2026, 9, 1), note = "mid, id a")
        seedLog("c", "e1", "t-monthly", LocalDate.of(2026, 1, 3), note = "oldest")
        seedLog("elsewhere", "e2", null, LocalDate.of(2026, 10, 14), note = "another elevator")

        val entries = repo.log("e1")

        assertEquals(listOf("z", "a", "b", "c"), entries.map { it.id })
        assertEquals(emptyList<LogEntryEntity>(), repo.log("no-such-elevator"))
    }
}
