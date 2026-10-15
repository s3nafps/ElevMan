package com.elevman.data

import androidx.room.withTransaction
import com.elevman.domain.isOverdue
import com.elevman.domain.nextDueDate
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * One maintenance task of an elevator, ready to display. [elevatorTaskId] is the id of the
 * [ElevatorTaskEntity], [taskName] the name of its maintenance task, [due] the date it is next due
 * and [overdue] whether that date has already passed.
 */
data class DueItem(
    val elevatorTaskId: String,
    val taskName: String,
    val due: LocalDate,
    val overdue: Boolean,
)

/**
 * Elevators, the maintenance tasks assigned to them, and their log of completed work and notes.
 * "Today" is `LocalDate.now(clock)`, and every row written gets `updatedAt` from [clock].
 */
class ElevatorRepository(
    private val db: ElevManDatabase,
    private val clock: Clock,
) {
    private val elevators get() = db.elevatorDao()
    private val elevatorTasks get() = db.elevatorTaskDao()
    private val maintenanceTasks get() = db.maintenanceTaskDao()
    private val logEntries get() = db.logEntryDao()

    /** Adds an elevator and returns its new id. */
    suspend fun addElevator(
        site: String,
        customer: String,
        modelId: String,
        serialNumber: String,
        notes: String,
    ): String {
        val id = newId()
        elevators.upsertAll(
            listOf(
                ElevatorEntity(
                    id = id,
                    site = site,
                    customer = customer,
                    modelId = modelId,
                    serialNumber = serialNumber,
                    notes = notes,
                    updatedAt = clock.millis(),
                ),
            ),
        )
        return id
    }

    /**
     * Assigns a maintenance task to an elevator, starting today, and returns the id of the
     * [ElevatorTaskEntity]. The task is first due one interval after today.
     *
     * Idempotent: when the elevator already has this task, nothing is inserted and the id of the
     * existing row is returned, with its original start date.
     */
    suspend fun assignTask(elevatorId: String, maintenanceTaskId: String): String =
        db.withTransaction {
            elevatorTasks.findByElevatorAndTask(elevatorId, maintenanceTaskId)?.id ?: run {
                val id = newId()
                elevatorTasks.upsertAll(
                    listOf(
                        ElevatorTaskEntity(
                            id = id,
                            elevatorId = elevatorId,
                            maintenanceTaskId = maintenanceTaskId,
                            startDate = today(),
                            updatedAt = clock.millis(),
                        ),
                    ),
                )
                id
            }
        }

    /**
     * Records that the elevator task [elevatorTaskId] was done on [date]. Backdated dates are fine.
     * The next due date follows from the latest log entry for the task, see [dueItems].
     *
     * @throws IllegalArgumentException when [date] is after today, or no elevator task has this id.
     */
    suspend fun markDone(elevatorTaskId: String, date: LocalDate, note: String) {
        requireNotFuture(date)
        db.withTransaction {
            val elevatorTask = requireNotNull(elevatorTasks.findById(elevatorTaskId)) {
                "No elevator task with id $elevatorTaskId"
            }
            logEntries.upsertAll(
                listOf(
                    LogEntryEntity(
                        id = newId(),
                        elevatorId = elevatorTask.elevatorId,
                        maintenanceTaskId = elevatorTask.maintenanceTaskId,
                        date = date,
                        note = note,
                        updatedAt = clock.millis(),
                    ),
                ),
            )
        }
    }

    /**
     * Adds a note, not tied to any task, to the log of [elevatorId].
     *
     * @throws IllegalArgumentException when [date] is after today.
     */
    suspend fun addNote(elevatorId: String, date: LocalDate, note: String) {
        requireNotFuture(date)
        logEntries.upsertAll(
            listOf(
                LogEntryEntity(
                    id = newId(),
                    elevatorId = elevatorId,
                    maintenanceTaskId = null,
                    date = date,
                    note = note,
                    updatedAt = clock.millis(),
                ),
            ),
        )
    }

    /**
     * The maintenance tasks of [elevatorId] with their due dates: overdue items first, then by due
     * date, then by task name, then by elevator task id. A task is due one interval after its latest
     * log entry for this elevator, or after the day it was assigned when it has none.
     *
     * An elevator task whose maintenance task no longer exists has no interval to count from, so it
     * is left out.
     */
    suspend fun dueItems(elevatorId: String): List<DueItem> {
        val today = today()
        return db.withTransaction {
            val lastDoneByTask = logEntries.findByElevatorId(elevatorId)
                .filter { it.maintenanceTaskId != null }
                .groupBy { it.maintenanceTaskId }
                .mapValues { (_, entries) -> entries.maxOf { it.date } }
            elevatorTasks.findByElevatorId(elevatorId).mapNotNull { elevatorTask ->
                val task = maintenanceTasks.findById(elevatorTask.maintenanceTaskId) ?: return@mapNotNull null
                val due = nextDueDate(
                    startDate = elevatorTask.startDate,
                    lastDone = lastDoneByTask[elevatorTask.maintenanceTaskId],
                    intervalMonths = task.intervalMonths,
                )
                DueItem(elevatorTask.id, task.name, due, isOverdue(due, today))
            }
        }.sortedWith(
            compareByDescending<DueItem> { it.overdue }
                .thenBy { it.due }
                .thenBy { it.taskName }
                .thenBy { it.elevatorTaskId },
        )
    }

    /** Every log entry of [elevatorId], newest date first, then by id. */
    suspend fun log(elevatorId: String): List<LogEntryEntity> =
        logEntries.findByElevatorId(elevatorId)
            .sortedWith(compareByDescending<LogEntryEntity> { it.date }.thenBy { it.id })

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun requireNotFuture(date: LocalDate) {
        require(!date.isAfter(today())) { "date $date is in the future" }
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
