package com.elevman.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.data.ElevManDatabase
import com.elevman.data.ElevatorEntity
import com.elevman.data.ElevatorModelEntity
import com.elevman.data.ElevatorTaskEntity
import com.elevman.data.ErrorCodeEntity
import com.elevman.data.LogEntryEntity
import com.elevman.data.MaintenanceTaskEntity
import com.elevman.data.ManualEntity
import com.elevman.data.ManufacturerEntity
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupApplierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: ElevManDatabase
    private lateinit var filesDir: File
    private lateinit var stagingDir: File
    private lateinit var writer: BackupWriter

    private val copyCalls = mutableListOf<Pair<File, File>>()
    private val writtenTables = mutableListOf<String>()

    private val clock = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC)
    private val staleMessage = "The local data changed since the preview. Preview the backup again."

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesDir = tempFolder.newFolder("files")
        stagingDir = tempFolder.newFolder("staging")
        writer = BackupWriter(db, filesDir, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun applier(
        copyPdf: (File, File) -> Unit = { from, to -> from.copyTo(to) },
        onWrite: (String) -> Unit = {},
    ) = BackupApplier(
        db = db,
        filesDir = filesDir,
        copyPdf = { from, to ->
            copyCalls += from to to
            copyPdf(from, to)
        },
        onWrite = { table ->
            writtenTables += table
            onWrite(table)
        },
    )

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

    private fun manual(id: String, title: String, updatedAt: Long) =
        ManualEntity(id, "model-1", title, "hash-$id", 3, updatedAt)

    /** A staged backup: [document] plus `manuals/<id>.pdf` under the staging folder for each of [pdfs]. */
    private fun valid(document: BackupDocument, pdfs: Map<String, ByteArray> = emptyMap()): ReadResult.Valid {
        pdfs.forEach { (id, bytes) ->
            val file = File(stagingDir, backupManualEntryName(id))
            file.parentFile!!.mkdirs()
            file.writeBytes(bytes)
        }
        return ReadResult.Valid(document, stagingDir)
    }

    private suspend fun planFor(valid: ReadResult.Valid): MergePlan =
        MergePlanner.plan(writer.snapshot(), valid.document)

    private fun localPdf(id: String) = File(File(filesDir, "manuals"), "$id.pdf")

    private fun seedLocalPdf(id: String, bytes: ByteArray) {
        localPdf(id).also { it.parentFile!!.mkdirs() }.writeBytes(bytes)
    }

    /** Every file under `filesDir/manuals`, by name; empty when the folder does not exist. */
    private fun manualsOnDisk(): List<String> =
        File(filesDir, "manuals").list()?.sorted().orEmpty()

    private val pdfOne = "%PDF-1.7 manual one".toByteArray()
    private val pdfTwo = ByteArray(5_000) { (it % 251).toByte() }
    private val pdfOld = "%PDF-1.4 old".toByteArray()

    private suspend fun assertDatabaseEquals(expected: BackupDocument) {
        val actual = writer.snapshot()
        assertEquals(expected.manufacturers.toSet(), actual.manufacturers.toSet())
        assertEquals(expected.models.toSet(), actual.models.toSet())
        assertEquals(expected.manuals.toSet(), actual.manuals.toSet())
        assertEquals(expected.errorCodes.toSet(), actual.errorCodes.toSet())
        assertEquals(expected.maintenanceTasks.toSet(), actual.maintenanceTasks.toSet())
        assertEquals(expected.elevators.toSet(), actual.elevators.toSet())
        assertEquals(expected.elevatorTasks.toSet(), actual.elevatorTasks.toSet())
        assertEquals(expected.logEntries.toSet(), actual.logEntries.toSet())
    }

    @Test
    fun appliesPlanAndCopiesPdfs() = runTest {
        // Local: a local-only row, a row the incoming side will update, a tie and a local-newer row.
        db.manufacturerDao().upsertAll(
            listOf(
                ManufacturerEntity("mf-local", "Local only", 1L),
                ManufacturerEntity("mf-upd", "Otis", 10L),
                ManufacturerEntity("mf-tie", "Tie local", 15L),
                ManufacturerEntity("mf-newer", "Local newer", 30L),
            ),
        )
        db.manualDao().upsertAll(listOf(manual("manual-keep", "Local manual", 50L)))
        seedLocalPdf("manual-keep", pdfOld)

        val added = document(
            manufacturers = listOf(ManufacturerEntity("mf-new", "Kone", 3L)),
            models = listOf(ElevatorModelEntity("model-1", "mf-new", "Gen2", "notes", 4L)),
            manuals = listOf(manual("manual-1", "Service manual", 5L), manual("manual-2", "Install manual", 5L)),
            errorCodes = listOf(
                ErrorCodeEntity(
                    id = "ec-1",
                    modelId = "model-1",
                    manualId = "manual-1",
                    code = "E 12",
                    normalizedCode = "E12",
                    meaning = "Door fault",
                    pageNumber = 7,
                    updatedAt = 6L,
                ),
            ),
            maintenanceTasks = listOf(
                MaintenanceTaskEntity("mt-1", "model-1", "manual-1", "Lubricate", 6, 9, updatedAt = 7L),
            ),
            elevators = listOf(ElevatorEntity("el-1", "Tower A", "Acme", "model-1", "SN-1", "", 8L)),
            elevatorTasks = listOf(ElevatorTaskEntity("et-1", "el-1", "mt-1", LocalDate.of(2026, 10, 15), 9L)),
            logEntries = listOf(LogEntryEntity("log-1", "el-1", null, LocalDate.of(2026, 10, 16), "Visited", 10L)),
        )
        val incoming = added.copy(
            manufacturers = added.manufacturers + listOf(
                ManufacturerEntity("mf-upd", "Otis Elevator", 20L),
                ManufacturerEntity("mf-tie", "Tie incoming", 15L),
                ManufacturerEntity("mf-newer", "Incoming older", 5L),
            ),
            // Same id as a local manual that is newer: the local row and its PDF stay.
            manuals = added.manuals + manual("manual-keep", "Incoming manual", 40L),
        )
        val valid = valid(
            incoming,
            mapOf("manual-1" to pdfOne, "manual-2" to pdfTwo, "manual-keep" to "incoming bytes".toByteArray()),
        )
        val plan = planFor(valid)
        assertEquals("one row per table plus a second manual", 9, plan.added)
        assertEquals(1, plan.updated)

        applier().apply(valid, plan)

        assertDatabaseEquals(
            incoming.copy(
                manufacturers = listOf(
                    ManufacturerEntity("mf-local", "Local only", 1L),
                    ManufacturerEntity("mf-upd", "Otis Elevator", 20L),
                    ManufacturerEntity("mf-tie", "Tie local", 15L),
                    ManufacturerEntity("mf-newer", "Local newer", 30L),
                    ManufacturerEntity("mf-new", "Kone", 3L),
                ),
                manuals = added.manuals + manual("manual-keep", "Local manual", 50L),
            ),
        )
        assertArrayEquals(pdfOne, localPdf("manual-1").readBytes())
        assertArrayEquals(pdfTwo, localPdf("manual-2").readBytes())
        assertArrayEquals("the losing manual keeps its local PDF", pdfOld, localPdf("manual-keep").readBytes())
        assertEquals(listOf("manual-1.pdf", "manual-2.pdf", "manual-keep.pdf"), manualsOnDisk())
        assertEquals(
            "only added or updated manuals are copied",
            setOf("manual-1.pdf", "manual-2.pdf"),
            copyCalls.map { it.second.name }.toSet(),
        )
        assertTrue(copyCalls.all { it.first.parentFile == File(stagingDir, "manuals") })
        assertEquals(
            listOf(
                "ManufacturerEntity",
                "ElevatorModelEntity",
                "ManualEntity",
                "ErrorCodeEntity",
                "MaintenanceTaskEntity",
                "ElevatorEntity",
                "ElevatorTaskEntity",
                "LogEntryEntity",
            ),
            writtenTables,
        )
    }

    @Test
    fun failureRollsBackDatabaseAndRemovesCopiedPdfs() = runTest {
        db.manufacturerDao().upsertAll(listOf(ManufacturerEntity("mf-1", "Otis", 10L)))
        val before = writer.snapshot()
        val incoming = document(
            manufacturers = listOf(ManufacturerEntity("mf-1", "Otis Elevator", 20L), ManufacturerEntity("mf-2", "Kone", 3L)),
            manuals = listOf(manual("manual-1", "Service manual", 5L), manual("manual-2", "Install manual", 5L)),
            elevators = listOf(ElevatorEntity("el-1", "Tower A", "Acme", "model-1", "SN-1", "", 8L)),
        )
        val valid = valid(incoming, mapOf("manual-1" to pdfOne, "manual-2" to pdfTwo))
        val plan = planFor(valid)

        try {
            applier(onWrite = { if (it == "ElevatorEntity") throw IOException("disk full") }).apply(valid, plan)
            fail("expected the write failure to propagate")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }

        assertEquals(
            "the tables before elevators were written inside the transaction, then rolled back",
            listOf("ManufacturerEntity", "ElevatorModelEntity", "ManualEntity", "ErrorCodeEntity", "MaintenanceTaskEntity", "ElevatorEntity"),
            writtenTables,
        )
        assertEquals(before.manufacturers, db.manufacturerDao().getAll())
        assertDatabaseEquals(before)
        assertEquals("no PDFs remain from this import", emptyList<String>(), manualsOnDisk())
        assertEquals(2, copyCalls.size)
    }

    @Test
    fun copyFailureRemovesPdfsAlreadyCopiedAndWritesNothing() = runTest {
        db.manufacturerDao().upsertAll(listOf(ManufacturerEntity("mf-1", "Otis", 10L)))
        val before = writer.snapshot()
        val incoming = document(
            manufacturers = listOf(ManufacturerEntity("mf-1", "Otis Elevator", 20L)),
            manuals = listOf(manual("manual-1", "Service manual", 5L), manual("manual-2", "Install manual", 5L)),
        )
        val valid = valid(incoming, mapOf("manual-1" to pdfOne, "manual-2" to pdfTwo))
        val plan = planFor(valid)

        try {
            applier(
                copyPdf = { from, to ->
                    if (to.name == "manual-2.pdf") throw IOException("no space left")
                    from.copyTo(to)
                },
            ).apply(valid, plan)
            fail("expected the copy failure to propagate")
        } catch (e: IOException) {
            assertEquals("no space left", e.message)
        }

        assertEquals(2, copyCalls.size)
        assertEquals(emptyList<String>(), manualsOnDisk())
        assertEquals(emptyList<String>(), writtenTables)
        assertDatabaseEquals(before)
    }

    @Test
    fun partialCopyLeftByAFailingCopyIsRemovedToo() = runTest {
        val incoming = document(manuals = listOf(manual("manual-1", "Service manual", 5L)))
        val valid = valid(incoming, mapOf("manual-1" to pdfOne))
        val plan = planFor(valid)

        try {
            applier(
                copyPdf = { from, to ->
                    from.copyTo(to)
                    throw IOException("cut short")
                },
            ).apply(valid, plan)
            fail("expected the copy failure to propagate")
        } catch (e: IOException) {
            assertEquals("cut short", e.message)
        }

        assertEquals(emptyList<String>(), manualsOnDisk())
    }

    @Test
    fun stalePreviewIsRejectedAndWritesNothing() = runTest {
        db.manufacturerDao().upsertAll(listOf(ManufacturerEntity("mf-1", "Otis", 10L)))
        val incoming = document(
            manufacturers = listOf(ManufacturerEntity("mf-1", "Otis Elevator", 20L)),
            manuals = listOf(manual("manual-1", "Service manual", 5L)),
        )
        val valid = valid(incoming, mapOf("manual-1" to pdfOne))
        val plan = planFor(valid)
        assertEquals(1, plan.added)
        assertEquals(1, plan.updated)

        // The preview was shown; then the local row was edited and is now newer than the incoming one.
        db.manufacturerDao().upsertAll(listOf(ManufacturerEntity("mf-1", "Otis edited", 30L)))
        val afterEdit = writer.snapshot()

        try {
            applier().apply(valid, plan)
            fail("expected the stale preview to be rejected")
        } catch (e: IllegalStateException) {
            assertEquals(staleMessage, e.message)
        }

        assertDatabaseEquals(afterEdit)
        assertEquals(emptyList<String>(), manualsOnDisk())
        assertEquals("the check runs before any PDF is copied", emptyList<Pair<File, File>>(), copyCalls)
        assertEquals(emptyList<String>(), writtenTables)
    }

    @Test
    fun previewIsStaleWhenAnAddedRowAppearedLocally() = runTest {
        val incoming = document(manuals = listOf(manual("manual-1", "Service manual", 5L)))
        val valid = valid(incoming, mapOf("manual-1" to pdfOne))
        val plan = planFor(valid)
        assertEquals(1, plan.added)

        // The very same record arrived locally in the meantime, so there is nothing left to add.
        db.manualDao().upsertAll(incoming.manuals)

        try {
            applier().apply(valid, plan)
            fail("expected the stale preview to be rejected")
        } catch (e: IllegalStateException) {
            assertEquals(staleMessage, e.message)
        }

        assertEquals(emptyList<Pair<File, File>>(), copyCalls)
        assertEquals(emptyList<String>(), manualsOnDisk())
    }

    @Test
    fun dataChangingWhileCopyingIsCaughtAndCopiesAreRemoved() = runTest {
        db.manufacturerDao().upsertAll(listOf(ManufacturerEntity("mf-1", "Otis", 10L)))
        val incoming = document(
            manufacturers = listOf(ManufacturerEntity("mf-1", "Otis Elevator", 20L)),
            manuals = listOf(manual("manual-1", "Service manual", 5L)),
        )
        val valid = valid(incoming, mapOf("manual-1" to pdfOne))
        val plan = planFor(valid)

        try {
            applier(
                copyPdf = { from, to ->
                    from.copyTo(to)
                    // The preview was still fresh when the copying began; a local edit lands before the write.
                    runBlocking { db.manufacturerDao().upsertAll(listOf(ManufacturerEntity("mf-1", "Edited", 30L))) }
                },
            ).apply(valid, plan)
            fail("expected the stale preview to be rejected")
        } catch (e: IllegalStateException) {
            assertEquals(staleMessage, e.message)
        }

        assertEquals(listOf(ManufacturerEntity("mf-1", "Edited", 30L)), db.manufacturerDao().getAll())
        assertTrue(db.manualDao().getAll().isEmpty())
        assertEquals(emptyList<String>(), manualsOnDisk())
        assertEquals(emptyList<String>(), writtenTables)
    }

    @Test
    fun tieWithDifferentContentKeepsLocalRow() = runTest {
        db.manufacturerDao().upsertAll(listOf(ManufacturerEntity("mf-1", "Local", 15L)))
        val incoming = document(manufacturers = listOf(ManufacturerEntity("mf-1", "Incoming", 15L)))
        val valid = valid(incoming)
        val plan = planFor(valid)
        assertEquals(0, plan.updated)

        applier().apply(valid, plan)

        assertEquals(listOf(ManufacturerEntity("mf-1", "Local", 15L)), db.manufacturerDao().getAll())
    }

    @Test
    fun updatedManualReplacesItsExistingPdf() = runTest {
        db.manualDao().upsertAll(listOf(manual("manual-1", "Old title", 10L)))
        seedLocalPdf("manual-1", pdfOld)
        val incoming = document(manuals = listOf(manual("manual-1", "New title", 20L)))
        val valid = valid(incoming, mapOf("manual-1" to pdfOne))
        val plan = planFor(valid)
        assertEquals(1, plan.updated)

        applier().apply(valid, plan)

        assertEquals(listOf(manual("manual-1", "New title", 20L)), db.manualDao().getAll())
        assertArrayEquals(pdfOne, localPdf("manual-1").readBytes())
        assertEquals("no stand-in file is left behind", listOf("manual-1.pdf"), manualsOnDisk())
    }

    @Test
    fun failureRestoresTheExistingPdfOfAnUpdatedManual() = runTest {
        db.manualDao().upsertAll(listOf(manual("manual-1", "Old title", 10L)))
        seedLocalPdf("manual-1", pdfOld)
        val before = writer.snapshot()
        val incoming = document(
            manuals = listOf(manual("manual-1", "New title", 20L)),
            elevators = listOf(ElevatorEntity("el-1", "Tower A", "Acme", "model-1", "SN-1", "", 8L)),
        )
        val valid = valid(incoming, mapOf("manual-1" to pdfOne))
        val plan = planFor(valid)

        try {
            applier(onWrite = { if (it == "ElevatorEntity") throw IOException("disk full") }).apply(valid, plan)
            fail("expected the write failure to propagate")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }

        assertDatabaseEquals(before)
        assertArrayEquals("the user's own PDF must survive a failed import", pdfOld, localPdf("manual-1").readBytes())
        assertEquals(listOf("manual-1.pdf"), manualsOnDisk())
    }

    @Test
    fun anEmptyPlanChangesNothing() = runTest {
        db.manufacturerDao().upsertAll(listOf(ManufacturerEntity("mf-1", "Otis", 10L)))
        val before = writer.snapshot()
        val valid = valid(document(manufacturers = listOf(ManufacturerEntity("mf-1", "Otis", 10L))))
        val plan = planFor(valid)
        assertEquals(MergePlan(0, 0, emptyList()), plan)

        applier().apply(valid, plan)

        assertDatabaseEquals(before)
        assertFalse(File(filesDir, "manuals").exists() && manualsOnDisk().isNotEmpty())
        assertEquals(emptyList<Pair<File, File>>(), copyCalls)
    }
}
