package com.elevman.backup

import androidx.room.withTransaction
import com.elevman.data.ElevManDatabase
import com.elevman.data.ManualEntity
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // One import at a time: two overlapping ones would set aside, overwrite and delete each other's PDFs.
    private val lock = Mutex()

    /**
     * Applies [valid]'s document, as previewed in [plan]. Calls on one applier run one after the other.
     *
     * Throws [IllegalStateException] before copying or writing anything if the local data no longer
     * produces [plan], that is, it changed since the preview, or if an earlier import left a
     * `.replaced` file behind. If copying a PDF or any write fails, the database is rolled back,
     * every PDF this call added is deleted, every PDF it replaced is put back, and the failure is
     * rethrown. Once the database has committed, the PDFs stay whatever happens afterwards.
     */
    suspend fun apply(valid: ReadResult.Valid, plan: MergePlan) {
        lock.withLock {
            val winners = db.withTransaction { currentWinners(valid, plan) }
            withContext(Dispatchers.IO) {
                check(winners.manuals.none { setAsideFile(pdfTarget(it)).exists() }) { INTERRUPTED_IMPORT }
            }

            val copies = PdfCopies()
            var committed = false
            try {
                withContext(Dispatchers.IO) {
                    for (manual in winners.manuals) {
                        copies.copy(
                            from = File(valid.stagingDir, backupManualEntryName(manual.id)),
                            to = pdfTarget(manual),
                            copyPdf = copyPdf,
                        )
                    }
                }
                // Not cancellable: a cancelled transaction can still commit, and the cleanup below
                // would then delete the PDFs of rows that exist.
                withContext(NonCancellable) {
                    db.withTransaction {
                        // The data can change while the PDFs are copied; write only what was copied
                        // for, and only if it is still what the user previewed.
                        val fresh = currentWinners(valid, plan)
                        check(fresh == winners) { STALE_PREVIEW }
                        check(fresh.toPlan() == plan) { STALE_PREVIEW }
                        write(winners)
                    }
                    committed = true
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    // A throw after the commit must not delete the PDFs of rows that exist.
                    if (committed) copies.commit() else copies.rollback(failure)
                }
                throw failure
            }
            withContext(NonCancellable + Dispatchers.IO) { copies.commit() }
        }
    }

    /** Where manual [manual]'s PDF lives; the same layout as the backup zip. */
    private fun pdfTarget(manual: ManualEntity) = File(filesDir, backupManualEntryName(manual.id))

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
                // No REPLACE_EXISTING: a file already set aside is somebody's original, never overwrite it.
                val setAside = setAsideFile(to)
                Files.move(to.toPath(), setAside.toPath())
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

        /**
         * Undoes every copy and carries on past a step that fails. Each problem is attached to
         * [cause] as suppressed, not thrown over it.
         */
        fun rollback(cause: Throwable) {
            for (file in created) {
                try {
                    file.delete()
                } catch (e: Exception) {
                    cause.addSuppressed(e)
                }
            }
            for ((target, setAside) in replaced) {
                try {
                    Files.move(setAside.toPath(), target.toPath(), REPLACE_EXISTING)
                } catch (e: Exception) {
                    cause.addSuppressed(e)
                }
            }
        }
    }

    private companion object {
        const val STALE_PREVIEW = "The local data changed since the preview. Preview the backup again."
        const val INTERRUPTED_IMPORT = "A previous import was interrupted. Restart the app and preview again."
        const val REPLACED_SUFFIX = ".replaced"

        /** Where an existing PDF waits while an import replaces it. */
        fun setAsideFile(target: File) = File(target.parentFile, target.name + REPLACED_SUFFIX)
    }
}
