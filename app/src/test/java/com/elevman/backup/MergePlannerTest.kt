package com.elevman.backup

import com.elevman.data.ElevatorEntity
import com.elevman.data.ElevatorModelEntity
import com.elevman.data.ElevatorTaskEntity
import com.elevman.data.ErrorCodeEntity
import com.elevman.data.LogEntryEntity
import com.elevman.data.MaintenanceTaskEntity
import com.elevman.data.ManualEntity
import com.elevman.data.ManufacturerEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM tests: the planner is pure and works on [BackupDocument] values built in code. */
class MergePlannerTest {

    private fun document(
        manufacturers: List<ManufacturerEntity> = emptyList(),
        models: List<ElevatorModelEntity> = emptyList(),
        manuals: List<ManualEntity> = emptyList(),
        errorCodes: List<ErrorCodeEntity> = emptyList(),
        maintenanceTasks: List<MaintenanceTaskEntity> = emptyList(),
        elevators: List<ElevatorEntity> = emptyList(),
        elevatorTasks: List<ElevatorTaskEntity> = emptyList(),
        logEntries: List<LogEntryEntity> = emptyList(),
    ) = BackupDocument(
        version = BACKUP_VERSION,
        exportedAt = 1_800_000_000_000L,
        manufacturers = manufacturers,
        models = models,
        manuals = manuals,
        errorCodes = errorCodes,
        maintenanceTasks = maintenanceTasks,
        elevators = elevators,
        elevatorTasks = elevatorTasks,
        logEntries = logEntries,
    )

    /** One row per table with fixed ids; [tag] varies the content and [updatedAt] the timestamp. */
    private fun everyTable(tag: String, updatedAt: Long) = document(
        manufacturers = listOf(ManufacturerEntity("mf-1", "Otis-$tag", updatedAt)),
        models = listOf(ElevatorModelEntity("model-1", "mf-1", "Gen2-$tag", "notes", updatedAt)),
        manuals = listOf(ManualEntity("manual-1", "model-1", "Manual-$tag", "hash-$tag", 3, updatedAt)),
        errorCodes = listOf(
            ErrorCodeEntity(
                id = "ec-1",
                modelId = "model-1",
                manualId = "manual-1",
                code = "E1",
                normalizedCode = "E1",
                meaning = "Door-$tag",
                pageNumber = 7,
                updatedAt = updatedAt,
            ),
        ),
        maintenanceTasks = listOf(
            MaintenanceTaskEntity(
                id = "mt-1",
                modelId = "model-1",
                manualId = "manual-1",
                name = "Lube-$tag",
                intervalMonths = 6,
                pageNumber = 9,
                updatedAt = updatedAt,
            ),
        ),
        elevators = listOf(ElevatorEntity("el-1", "Tower", "Acme", "model-1", "SN-$tag", "", updatedAt)),
        elevatorTasks = listOf(
            ElevatorTaskEntity("et-1", "el-1", "mt-$tag", LocalDate.of(2026, 10, 15), updatedAt),
        ),
        logEntries = listOf(
            LogEntryEntity("log-1", "el-1", null, LocalDate.of(2026, 10, 15), "Visited-$tag", updatedAt),
        ),
    )

    private fun manufacturer(name: String, updatedAt: Long, id: String = "mf-1") =
        ManufacturerEntity(id, name, updatedAt)

    @Test
    fun newerIncomingWinsAndIsUpdated() {
        val local = document(manufacturers = listOf(manufacturer("Otis", 10L)))
        val incoming = document(manufacturers = listOf(manufacturer("Otis Elevator", 20L)))

        val plan = MergePlanner.plan(local, incoming)

        assertEquals(
            MergePlan(
                added = 0,
                updated = 1,
                // The overridden record is the local version, which the incoming side replaces.
                overridden = listOf(Overridden("ManufacturerEntity", "mf-1", "Otis", Side.INCOMING)),
            ),
            plan,
        )
        assertEquals(incoming.manufacturers, MergePlanner.winners(local, incoming).manufacturers)
    }

    @Test
    fun olderIncomingLosesAndIsOverridden() {
        val local = document(manufacturers = listOf(manufacturer("Otis", 20L)))
        val incoming = document(manufacturers = listOf(manufacturer("Otis Elevator", 10L)))

        val plan = MergePlanner.plan(local, incoming)

        assertEquals(
            MergePlan(
                added = 0,
                updated = 0,
                // The overridden record is the incoming version, which the local side beats.
                overridden = listOf(Overridden("ManufacturerEntity", "mf-1", "Otis Elevator", Side.LOCAL)),
            ),
            plan,
        )
        assertTrue(MergePlanner.winners(local, incoming).manufacturers.isEmpty())
    }

    @Test
    fun tieKeepsLocal() {
        val local = document(manufacturers = listOf(manufacturer("Otis", 15L)))
        val incoming = document(manufacturers = listOf(manufacturer("Otis Elevator", 15L)))

        val plan = MergePlanner.plan(local, incoming)

        assertEquals(0, plan.updated)
        assertEquals(
            listOf(Overridden("ManufacturerEntity", "mf-1", "Otis Elevator", Side.LOCAL)),
            plan.overridden,
        )
        assertTrue(MergePlanner.winners(local, incoming).manufacturers.isEmpty())
    }

    @Test
    fun identicalContentDoesNothing() {
        val local = everyTable("same", 10L)
        val incoming = everyTable("same", 10L)

        assertEquals(MergePlan(0, 0, emptyList()), MergePlanner.plan(local, incoming))
        val winners = MergePlanner.winners(local, incoming)
        assertEquals(0, winners.added)
        assertEquals(0, winners.updated)
        assertTrue(winners.manufacturers.isEmpty() && winners.logEntries.isEmpty())
    }

    @Test
    fun sameTimestampButOtherFieldsIsDifferentContentAndKeepsLocal() {
        // Content equality includes updatedAt, so an identical timestamp does not make rows equal.
        val local = document(models = listOf(ElevatorModelEntity("model-1", "mf-1", "Gen2", "old notes", 5L)))
        val incoming = document(models = listOf(ElevatorModelEntity("model-1", "mf-1", "Gen2", "new notes", 5L)))

        val plan = MergePlanner.plan(local, incoming)

        assertEquals(
            MergePlan(0, 0, listOf(Overridden("ElevatorModelEntity", "model-1", "Gen2", Side.LOCAL))),
            plan,
        )
    }

    @Test
    fun localOnlyRowsAreKept() {
        val local = document(
            manufacturers = listOf(manufacturer("Otis", 10L, id = "mf-1"), manufacturer("Kone", 10L, id = "mf-2")),
        )
        val incoming = document(manufacturers = listOf(manufacturer("Otis", 10L, id = "mf-1")))

        val plan = MergePlanner.plan(local, incoming)

        assertEquals(MergePlan(0, 0, emptyList()), plan)
        assertTrue(MergePlanner.winners(local, incoming).manufacturers.isEmpty())
    }

    @Test
    fun incomingOnlyRowsAreAdded() {
        val local = document(manufacturers = listOf(manufacturer("Otis", 10L, id = "mf-1")))
        val incoming = document(
            manufacturers = listOf(
                manufacturer("Otis", 10L, id = "mf-1"),
                manufacturer("Kone", 3L, id = "mf-2"),
                manufacturer("Schindler", 4L, id = "mf-3"),
            ),
        )

        val plan = MergePlanner.plan(local, incoming)

        assertEquals(MergePlan(added = 2, updated = 0, overridden = emptyList()), plan)
        assertEquals(
            listOf(manufacturer("Kone", 3L, id = "mf-2"), manufacturer("Schindler", 4L, id = "mf-3")),
            MergePlanner.winners(local, incoming).manufacturers,
        )
    }

    @Test
    fun overriddenListCarriesBothOutcomesAndTheLosingVersionsLabel() {
        val local = document(
            manufacturers = listOf(
                manufacturer("Local older", 10L, id = "mf-1"),
                manufacturer("Local newer", 30L, id = "mf-2"),
            ),
        )
        val incoming = document(
            manufacturers = listOf(
                manufacturer("Incoming newer", 20L, id = "mf-1"),
                manufacturer("Incoming older", 20L, id = "mf-2"),
            ),
        )

        val plan = MergePlanner.plan(local, incoming)

        assertEquals(
            listOf(
                // mf-1: incoming wins, so the local version is what was overridden.
                Overridden("ManufacturerEntity", "mf-1", "Local older", Side.INCOMING),
                // mf-2: local wins, so the incoming version is what was overridden.
                Overridden("ManufacturerEntity", "mf-2", "Incoming older", Side.LOCAL),
            ),
            plan.overridden,
        )
        assertEquals(1, plan.updated)
    }

    @Test
    fun everyTableIsMergedAndLabelledWhenIncomingWins() {
        val local = everyTable("local", 10L)
        val incoming = everyTable("incoming", 20L)

        val plan = MergePlanner.plan(local, incoming)

        assertEquals(0, plan.added)
        assertEquals(8, plan.updated)
        assertEquals(
            listOf(
                Overridden("ManufacturerEntity", "mf-1", "Otis-local", Side.INCOMING),
                Overridden("ElevatorModelEntity", "model-1", "Gen2-local", Side.INCOMING),
                Overridden("ManualEntity", "manual-1", "Manual-local", Side.INCOMING),
                Overridden("ErrorCodeEntity", "ec-1", "E1: Door-local", Side.INCOMING),
                Overridden("MaintenanceTaskEntity", "mt-1", "Lube-local", Side.INCOMING),
                Overridden("ElevatorEntity", "el-1", "Tower, SN-local", Side.INCOMING),
                Overridden("ElevatorTaskEntity", "et-1", "mt-local", Side.INCOMING),
                Overridden("LogEntryEntity", "log-1", "2026-10-15 Visited-local", Side.INCOMING),
            ),
            plan.overridden,
        )

        val winners = MergePlanner.winners(local, incoming)
        assertEquals(incoming.manufacturers, winners.manufacturers)
        assertEquals(incoming.models, winners.models)
        assertEquals(incoming.manuals, winners.manuals)
        assertEquals(incoming.errorCodes, winners.errorCodes)
        assertEquals(incoming.maintenanceTasks, winners.maintenanceTasks)
        assertEquals(incoming.elevators, winners.elevators)
        assertEquals(incoming.elevatorTasks, winners.elevatorTasks)
        assertEquals(incoming.logEntries, winners.logEntries)
    }

    @Test
    fun everyTableIsLabelledWhenLocalWins() {
        val local = everyTable("local", 20L)
        val incoming = everyTable("incoming", 10L)

        val plan = MergePlanner.plan(local, incoming)

        assertEquals(0, plan.updated)
        assertEquals(
            listOf(
                Overridden("ManufacturerEntity", "mf-1", "Otis-incoming", Side.LOCAL),
                Overridden("ElevatorModelEntity", "model-1", "Gen2-incoming", Side.LOCAL),
                Overridden("ManualEntity", "manual-1", "Manual-incoming", Side.LOCAL),
                Overridden("ErrorCodeEntity", "ec-1", "E1: Door-incoming", Side.LOCAL),
                Overridden("MaintenanceTaskEntity", "mt-1", "Lube-incoming", Side.LOCAL),
                Overridden("ElevatorEntity", "el-1", "Tower, SN-incoming", Side.LOCAL),
                Overridden("ElevatorTaskEntity", "et-1", "mt-incoming", Side.LOCAL),
                Overridden("LogEntryEntity", "log-1", "2026-10-15 Visited-incoming", Side.LOCAL),
            ),
            plan.overridden,
        )
    }

    @Test
    fun planAgreesWithWinnersOnCounts() {
        val local = everyTable("local", 10L)
        val incoming = everyTable("incoming", 20L).let {
            it.copy(manufacturers = it.manufacturers + manufacturer("Kone", 1L, id = "mf-2"))
        }

        val plan = MergePlanner.plan(local, incoming)
        val winners = MergePlanner.winners(local, incoming)

        assertEquals(1, plan.added)
        assertEquals(8, plan.updated)
        assertEquals(winners.added, plan.added)
        assertEquals(winners.updated, plan.updated)
        assertEquals(winners.overridden, plan.overridden)
    }
}
