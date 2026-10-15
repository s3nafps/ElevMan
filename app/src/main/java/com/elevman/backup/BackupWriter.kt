package com.elevman.backup

import androidx.room.withTransaction
import com.elevman.data.ElevManDatabase
import com.elevman.data.ManualEntity
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.Clock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exports the whole database and every manual PDF as one zip: `backup.json` first, then one
 * `manuals/<id>.pdf` entry per manual.
 *
 * The PDFs live at `filesDir/manuals/<id>.pdf` (see `ManualStore`).
 */
class BackupWriter(
    private val db: ElevManDatabase,
    private val filesDir: File,
    private val clock: Clock,
) {

    /** Reads every table in one transaction, so the lists agree with each other. */
    suspend fun snapshot(): BackupDocument = db.withTransaction {
        BackupDocument(
            version = BACKUP_VERSION,
            exportedAt = clock.millis(),
            manufacturers = db.manufacturerDao().getAll(),
            models = db.elevatorModelDao().getAll(),
            manuals = db.manualDao().getAll(),
            errorCodes = db.errorCodeDao().getAll(),
            maintenanceTasks = db.maintenanceTaskDao().getAll(),
            elevators = db.elevatorDao().getAll(),
            elevatorTasks = db.elevatorTaskDao().getAll(),
            logEntries = db.logEntryDao().getAll(),
        )
    }

    /** Uncompressed size of the export: the JSON in UTF-8 plus the PDFs that exist on disk. */
    suspend fun estimateSizeBytes(): Long {
        val document = snapshot()
        return withContext(Dispatchers.IO) {
            val pdfBytes = document.manuals.sumOf { manual ->
                manualFile(manual).takeIf { it.isFile }?.length() ?: 0L
            }
            document.toJsonBytes().size.toLong() + pdfBytes
        }
    }

    /**
     * Writes the backup zip to [out]. The caller owns [out] and closes it; the zip itself is
     * finished before this returns.
     *
     * Throws [IllegalStateException] before writing anything if a manual has no PDF on disk,
     * because the import rejects a backup whose PDFs are missing.
     */
    suspend fun write(out: OutputStream) {
        val document = snapshot()
        withContext(Dispatchers.IO) {
            val missing = document.manuals.filterNot { manualFile(it).isFile }
            check(missing.isEmpty()) {
                "Cannot export: no PDF file for " +
                    missing.joinToString { "manual ${it.id} (\"${it.title}\")" }
            }

            // ZipOutputStream.close() closes what it wraps; the caller's stream stays open.
            ZipOutputStream(NonClosingOutputStream(out)).use { zip ->
                writeJsonEntry(zip, document)
                document.manuals.forEach { writePdfEntry(zip, it) }
            }
        }
    }

    private fun writeJsonEntry(zip: ZipOutputStream, document: BackupDocument) {
        zip.putNextEntry(ZipEntry(BACKUP_JSON_ENTRY))
        zip.write(document.toJsonBytes())
        zip.closeEntry()
    }

    private fun writePdfEntry(zip: ZipOutputStream, manual: ManualEntity) {
        zip.putNextEntry(ZipEntry(backupManualEntryName(manual.id)))
        manualFile(manual).inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun manualFile(manual: ManualEntity): File = File(File(filesDir, "manuals"), "${manual.id}.pdf")

    /** Passes writes through and turns `close()` into a flush. */
    private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        // FilterOutputStream's own version writes byte by byte.
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
        }

        override fun close() {
            flush()
        }
    }
}
