package com.elevman.backup

import com.elevman.data.ElevatorEntity
import com.elevman.data.ElevatorModelEntity
import com.elevman.data.ElevatorTaskEntity
import com.elevman.data.ErrorCodeEntity
import com.elevman.data.LogEntryEntity
import com.elevman.data.MaintenanceTaskEntity
import com.elevman.data.ManualEntity
import com.elevman.data.ManufacturerEntity

/** Which side of a merge kept its version of a record. */
enum class Side { LOCAL, INCOMING }

/**
 * A record that exists on both sides with different content. [label] describes the version that
 * lost, and [winner] is the side whose version was kept: [Side.INCOMING] means [label] is the local
 * version that the import replaces, [Side.LOCAL] means it is the incoming version that was ignored.
 */
data class Overridden(val table: String, val id: String, val label: String, val winner: Side)

/** What importing a backup would do. Shown in the preview and checked again when applying. */
data class MergePlan(val added: Int, val updated: Int, val overridden: List<Overridden>)

/** Table names used in [Overridden.table] and passed to `BackupApplier`'s `onWrite`: the entity class names. */
object MergeTable {
    const val MANUFACTURERS = "ManufacturerEntity"
    const val MODELS = "ElevatorModelEntity"
    const val MANUALS = "ManualEntity"
    const val ERROR_CODES = "ErrorCodeEntity"
    const val MAINTENANCE_TASKS = "MaintenanceTaskEntity"
    const val ELEVATORS = "ElevatorEntity"
    const val ELEVATOR_TASKS = "ElevatorTaskEntity"
    const val LOG_ENTRIES = "LogEntryEntity"
}

/**
 * The incoming rows that must be written, per table: rows that are new locally, and rows whose
 * incoming version is newer. The counts and [overridden] cover all eight tables, in table order.
 */
data class MergeWinners(
    val manufacturers: List<ManufacturerEntity>,
    val models: List<ElevatorModelEntity>,
    val manuals: List<ManualEntity>,
    val errorCodes: List<ErrorCodeEntity>,
    val maintenanceTasks: List<MaintenanceTaskEntity>,
    val elevators: List<ElevatorEntity>,
    val elevatorTasks: List<ElevatorTaskEntity>,
    val logEntries: List<LogEntryEntity>,
    val added: Int,
    val updated: Int,
    val overridden: List<Overridden>,
) {
    fun toPlan() = MergePlan(added, updated, overridden)
}

/** Decides, record by record, what an import changes. Records are matched by table and `id`. */
object MergePlanner {

    fun plan(local: BackupDocument, incoming: BackupDocument): MergePlan = winners(local, incoming).toPlan()

    /**
     * The single place the merge rule lives; the preview ([plan]) and the apply both use it.
     *
     * An incoming id that is not local is added. For an id on both sides, equal records (data-class
     * equality, `updatedAt` included) change nothing; otherwise the incoming record wins only if its
     * `updatedAt` is strictly greater, so a tie or an older incoming record keeps the local one.
     * Local-only records are untouched. Either way a conflict is listed in [MergeWinners.overridden].
     */
    fun winners(local: BackupDocument, incoming: BackupDocument): MergeWinners {
        val merge = Merge()
        val manufacturers = merge.table(
            MergeTable.MANUFACTURERS, local.manufacturers, incoming.manufacturers,
            id = { it.id }, updatedAt = { it.updatedAt }, label = { it.name },
        )
        val models = merge.table(
            MergeTable.MODELS, local.models, incoming.models,
            id = { it.id }, updatedAt = { it.updatedAt }, label = { it.name },
        )
        val manuals = merge.table(
            MergeTable.MANUALS, local.manuals, incoming.manuals,
            id = { it.id }, updatedAt = { it.updatedAt }, label = { it.title },
        )
        val errorCodes = merge.table(
            MergeTable.ERROR_CODES, local.errorCodes, incoming.errorCodes,
            id = { it.id }, updatedAt = { it.updatedAt }, label = { "${it.code}: ${it.meaning}" },
        )
        val maintenanceTasks = merge.table(
            MergeTable.MAINTENANCE_TASKS, local.maintenanceTasks, incoming.maintenanceTasks,
            id = { it.id }, updatedAt = { it.updatedAt }, label = { it.name },
        )
        val elevators = merge.table(
            MergeTable.ELEVATORS, local.elevators, incoming.elevators,
            id = { it.id }, updatedAt = { it.updatedAt }, label = { "${it.site}, ${it.serialNumber}" },
        )
        val elevatorTasks = merge.table(
            MergeTable.ELEVATOR_TASKS, local.elevatorTasks, incoming.elevatorTasks,
            id = { it.id }, updatedAt = { it.updatedAt }, label = { it.maintenanceTaskId },
        )
        val logEntries = merge.table(
            MergeTable.LOG_ENTRIES, local.logEntries, incoming.logEntries,
            id = { it.id }, updatedAt = { it.updatedAt }, label = { "${it.date} ${it.note}" },
        )
        return MergeWinners(
            manufacturers, models, manuals, errorCodes, maintenanceTasks, elevators, elevatorTasks, logEntries,
            merge.added, merge.updated, merge.overridden,
        )
    }

    /** Running totals over the tables merged so far. */
    private class Merge {
        var added = 0
        var updated = 0
        val overridden = mutableListOf<Overridden>()

        fun <T> table(
            name: String,
            local: List<T>,
            incoming: List<T>,
            id: (T) -> String,
            updatedAt: (T) -> Long,
            label: (T) -> String,
        ): List<T> {
            val localById = local.associateBy(id)
            val winners = mutableListOf<T>()
            // A repeated incoming id counts once, the way writing the rows in order would leave only the last.
            for (row in incoming.associateBy(id).values) {
                val mine = localById[id(row)]
                when {
                    mine == null -> {
                        added++
                        winners += row
                    }
                    mine == row -> Unit
                    updatedAt(row) > updatedAt(mine) -> {
                        updated++
                        winners += row
                        overridden += Overridden(name, id(row), label(mine), Side.INCOMING)
                    }
                    else -> overridden += Overridden(name, id(row), label(row), Side.LOCAL)
                }
            }
            return winners
        }
    }
}
