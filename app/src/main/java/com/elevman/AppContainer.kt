package com.elevman

import android.content.Context
import androidx.room.Room
import com.elevman.backup.BackupApplier
import com.elevman.backup.BackupReader
import com.elevman.backup.BackupWriter
import com.elevman.data.CodeSearch
import com.elevman.data.ElevManDatabase
import com.elevman.data.ElevatorRepository
import com.elevman.data.ManualImporter
import com.elevman.data.ManualStore
import com.elevman.data.ReviewRepository
import com.elevman.data.manualPdfFile
import com.elevman.pdf.PageTextSource
import com.elevman.pdf.PdfBoxTextSource
import java.io.File
import java.time.Clock

/**
 * Builds the database and the services on top of it, once, and hands the same instances to every
 * screen.
 *
 * Each service is created exactly once. That matters for [backupApplier]: its lock only stops
 * overlapping imports that go through the same instance, so a second applier would let two
 * imports overwrite each other's PDFs.
 *
 * The database is opened lazily by Room, on the first query, so creating the container does no
 * disk work.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    // The device time zone, so "today" (maintenance due dates, overdue) is the user's local date.
    private val clock: Clock = Clock.systemDefaultZone()

    private val filesDir: File = appContext.filesDir

    val db: ElevManDatabase =
        Room.databaseBuilder(appContext, ElevManDatabase::class.java, DATABASE_NAME).build()

    val manualStore: ManualStore = ManualStore(filesDir, db.manualDao(), clock)

    /** Reads the text of PDF pages. One instance, shared by the importer and the PDF viewer. */
    val pageText: PageTextSource = PdfBoxTextSource()

    /** Where the PDF of manual [manualId] is stored. The file may not exist (it is not checked). */
    fun manualFile(manualId: String): File = manualPdfFile(filesDir, manualId)

    val importer: ManualImporter = ManualImporter(
        store = manualStore,
        text = pageText,
        manuals = db.manualDao(),
        codes = db.errorCodeDao(),
        tasks = db.maintenanceTaskDao(),
        clock = clock,
        db = db,
    )

    val review: ReviewRepository = ReviewRepository(db, clock)

    val search: CodeSearch = CodeSearch(db)

    val elevators: ElevatorRepository = ElevatorRepository(db, clock)

    val backupWriter: BackupWriter = BackupWriter(db, filesDir, clock)

    // Staging only holds a backup while it is being previewed, so the cache directory is the place.
    val backupReader: BackupReader = BackupReader(File(appContext.cacheDir, BACKUP_STAGING_DIR))

    val backupApplier: BackupApplier = BackupApplier(db, filesDir)

    private companion object {
        const val DATABASE_NAME = "elevman.db"
        const val BACKUP_STAGING_DIR = "backup-staging"
    }
}
