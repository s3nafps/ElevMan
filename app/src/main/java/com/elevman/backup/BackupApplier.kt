package com.elevman.backup

import androidx.room.withTransaction
import com.elevman.data.ElevManDatabase
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Applies a previewed import: the PDFs of added or updated manuals go into `filesDir/manuals/`,
 * then every winning row is written in one Room transaction. It is all or nothing.
 *
 * [copyPdf] copies a staged PDF to its final place, and [onWrite] is called with the table's name
 * (see [MergeTable]) right before that table's rows are written. Both exist so tests can fail a step.
 */
class BackupApplier(
    private val db: ElevManDatabase,
    private val filesDir: File,
    private val copyPdf: (from: File, to: File) -> Unit = { from, to -> from.copyTo(to) },
    private val onWrite: (table: String) -> Unit = {},
) {
    // Only the rows of the snapshot are used; its exportedAt stamp is not.
    private val localData = BackupWriter(db, filesDir, Clock.systemUTC())

    /**
     * Applies [valid]'s document, as previewed in [plan].
     *
     * Throws [IllegalStateException] before copying or writing anything if the local data no longer
     * produces [plan], that is, it changed since the preview. If copying a PDF or any write fails,
     * the database is rolled back, every PDF this call added is deleted, every PDF it replaced is
     * put back, and the failure is rethrown.
     */
    suspend fun apply(valid: ReadResult.Valid, plan: MergePlan) {
        val winners = db.withTransaction { currentWinners(valid, plan) }

        val copies = PdfCopies()
        try {
            withContext(Dispatchers.IO) {
                for (manual in winners.manuals) {
                    copies.copy(
                        from = File(valid.stagingDir, backupManualEntryName(manual.id)),
                        to = File(filesDir, backupManualEntryName(manual.id)),
                        copyPdf = copyPdf,
                    )
                }
            }
            // Not cancellable: a cancelled transaction can still commit, and the cleanup below
            // would then delete the PDFs of rows that exist.
            withContext(NonCancellable) {
                db.withTransaction {
                    // The data can change while the PDFs are copied; write only what was copied for.
                    check(currentWinners(valid, plan) == winners) { STALE_PREVIEW }
                    write(winners)
                }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { copies.rollback(failure) }
            throw failure
        }
        copies.commit()
    }

    /** The winners against the local data as it is now; throws if that is not what [plan] previewed. */
    private suspend fun currentWinners(valid: ReadResult.Valid, plan: MergePlan): MergeWinners {
        val winners = MergePlanner.winners(localData.snapshot(), valid.document)
        check(winners.added == plan.added && winners.updated == plan.updated) { STALE_PREVIEW }
        return winners
    }

    private suspend fun write(winners: MergeWinners) {
        onWrite(MergeTable.MANUFACTURERS)
        db.manufacturerDao().upsertAll(winners.manufacturers)
        onWrite(MergeTable.MODELS)
        db.elevatorModelDao().upsertAll(winners.models)
        onWrite(MergeTable.MANUALS)
        db.manualDao().upsertAll(winners.manuals)
        onWrite(MergeTable.ERROR_CODES)
        db.errorCodeDao().upsertAll(winners.errorCodes)
        onWrite(MergeTable.MAINTENANCE_TASKS)
        db.maintenanceTaskDao().upsertAll(winners.maintenanceTasks)
        onWrite(MergeTable.ELEVATORS)
        db.elevatorDao().upsertAll(winners.elevators)
        onWrite(MergeTable.ELEVATOR_TASKS)
        db.elevatorTaskDao().upsertAll(winners.elevatorTasks)
        onWrite(MergeTable.LOG_ENTRIES)
        db.logEntryDao().upsertAll(winners.logEntries)
    }

    /**
     * Remembers what the copy step did to the disk, so a failed import can undo it.
     *
     * A PDF that already exists (an updated manual) is set aside under [REPLACED_SUFFIX] first, so
     * the user's own file survives a rollback; it is deleted once the import has committed.
     */
    private class PdfCopies {
        private val created = mutableListOf<File>()
        private val replaced = mutableListOf<Pair<File, File>>() // target to the original set aside

        fun copy(from: File, to: File, copyPdf: (File, File) -> Unit) {
            val dir = checkNotNull(to.parentFile) { "PDF target has no parent folder: $to" }
            Files.createDirectories(dir.toPath())
            if (to.exists()) {
                val setAside = File(dir, to.name + REPLACED_SUFFIX)
                Files.move(to.toPath(), setAside.toPath(), REPLACE_EXISTING)
                replaced += to to setAside
            } else {
                // Listed before copying, so a half-written file from a failing copy is removed too.
                created += to
            }
            copyPdf(from, to)
        }

        fun commit() {
            replaced.forEach { (_, setAside) -> setAside.delete() }
        }

        /** Undoes every copy. A problem while doing so is attached to [cause], not thrown over it. */
        fun rollback(cause: Throwable) {
            created.forEach { it.delete() }
            for ((target, setAside) in replaced) {
                try {
                    Files.move(setAside.toPath(), target.toPath(), REPLACE_EXISTING)
                } catch (e: IOException) {
                    cause.addSuppressed(e)
                }
            }
        }
    }

    private companion object {
        const val STALE_PREVIEW = "The local data changed since the preview. Preview the backup again."
        const val REPLACED_SUFFIX = ".replaced"
    }
}
