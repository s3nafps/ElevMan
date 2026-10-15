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
import com.elevman.data.ReviewStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: ElevManDatabase
    private lateinit var filesDir: File
    private lateinit var writer: BackupWriter

    private val exportedAt = 1_800_000_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(exportedAt), ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesDir = tempFolder.newFolder("files")
        writer = BackupWriter(db, filesDir, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val pdfOne = "%PDF-1.7 manual one — ünïcode".toByteArray()
    private val pdfTwo = ByteArray(5_000) { (it % 251).toByte() }

    private suspend fun seedManual(id: String, title: String, pdf: ByteArray?) {
        db.manualDao().upsertAll(
            listOf(
                ManualEntity(
                    id = id,
                    modelId = "model-1",
                    title = title,
                    fileHash = "hash-$id",
                    pageCount = 3,
                    updatedAt = 10L,
                ),
            ),
        )
        if (pdf != null) {
            val dir = File(filesDir, "manuals").also { it.mkdirs() }
            File(dir, "$id.pdf").writeBytes(pdf)
        }
    }

    /** One row in every table, so a round trip proves all eight lists reach the document. */
    private suspend fun seedEveryTable() {
        db.manufacturerDao().upsertAll(listOf(ManufacturerEntity("mf-1", "Otis", 1L)))
        db.elevatorModelDao().upsertAll(
            listOf(ElevatorModelEntity("model-1", "mf-1", "Gen2", "notes", 2L)),
        )
        db.errorCodeDao().upsertAll(
            listOf(
                ErrorCodeEntity(
                    id = "ec-1",
                    modelId = "model-1",
                    manualId = "manual-1",
                    code = "E 12",
                    normalizedCode = "E12",
                    meaning = "Door fault",
                    causeAndFix = "Clean the sill",
                    pageNumber = 7,
                    status = ReviewStatus.CONFIRMED,
                    updatedAt = 4L,
                ),
            ),
        )
        db.maintenanceTaskDao().upsertAll(
            listOf(
                MaintenanceTaskEntity(
                    id = "mt-1",
                    modelId = "model-1",
                    manualId = "manual-1",
                    name = "Lubricate rails",
                    intervalMonths = 6,
                    pageNumber = 9,
                    status = ReviewStatus.CONFIRMED,
                    updatedAt = 5L,
                ),
            ),
        )
        db.elevatorDao().upsertAll(
            listOf(ElevatorEntity("el-1", "Tower A", "Acme", "model-1", "SN-1", "", 6L)),
        )
        db.elevatorTaskDao().upsertAll(
            listOf(ElevatorTaskEntity("et-1", "el-1", "mt-1", LocalDate.of(2026, 10, 15), 7L)),
        )
        db.logEntryDao().upsertAll(
            listOf(
                LogEntryEntity("log-1", "el-1", null, LocalDate.of(2026, 10, 16), "Visited", 8L),
            ),
        )
    }

    private class ZipContents(val names: List<String>, val bytes: Map<String, ByteArray>)

    private fun readZip(zipBytes: ByteArray): ZipContents {
        val names = mutableListOf<String>()
        val bytes = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                bytes[entry.name] = zip.readBytes()
            }
        }
        return ZipContents(names, bytes)
    }

    @Test
    fun zipContainsJsonAndPdfs() = runTest {
        seedEveryTable()
        seedManual("manual-1", "Gen2 Service Manual", pdfOne)
        seedManual("manual-2", "Gen2 Installation Manual", pdfTwo)
        val out = ByteArrayOutputStream()

        writer.write(out)

        val zip = readZip(out.toByteArray())
        assertEquals("backup.json must be the first entry", "backup.json", zip.names.first())
        assertEquals(
            listOf("manuals/manual-1.pdf", "manuals/manual-2.pdf"),
            zip.names.drop(1).sorted(),
        )
        assertArrayEquals(pdfOne, zip.bytes.getValue("manuals/manual-1.pdf"))
        assertArrayEquals(pdfTwo, zip.bytes.getValue("manuals/manual-2.pdf"))

        val doc = BackupJson.decodeFromString<BackupDocument>(
            zip.bytes.getValue("backup.json").toString(Charsets.UTF_8),
        )
        assertEquals(1, doc.version)
        assertEquals(exportedAt, doc.exportedAt)
        assertEquals(listOf(ManufacturerEntity("mf-1", "Otis", 1L)), doc.manufacturers)
        assertEquals(db.elevatorModelDao().getAll(), doc.models)
        assertEquals(setOf("manual-1", "manual-2"), doc.manuals.map { it.id }.toSet())
        assertEquals(db.errorCodeDao().getAll(), doc.errorCodes)
        assertEquals(db.maintenanceTaskDao().getAll(), doc.maintenanceTasks)
        assertEquals(db.elevatorDao().getAll(), doc.elevators)
        assertEquals(db.elevatorTaskDao().getAll(), doc.elevatorTasks)
        assertEquals(LocalDate.of(2026, 10, 15), doc.elevatorTasks.single().startDate)
        assertEquals(db.logEntryDao().getAll(), doc.logEntries)
        assertEquals(null, doc.logEntries.single().maintenanceTaskId)
    }

    @Test
    fun snapshotIsVersionOneStampedWithClock() = runTest {
        val doc = writer.snapshot()

        assertEquals(1, doc.version)
        assertEquals(exportedAt, doc.exportedAt)
        assertTrue(doc.manuals.isEmpty())
    }

    @Test
    fun estimateIsAtLeastPdfBytes() = runTest {
        seedManual("manual-1", "Gen2 Service Manual", pdfOne)
        seedManual("manual-2", "Gen2 Installation Manual", pdfTwo)
        val pdfBytes = (pdfOne.size + pdfTwo.size).toLong()

        val estimate = writer.estimateSizeBytes()

        assertTrue("estimate $estimate < pdf bytes $pdfBytes", estimate >= pdfBytes)
        val jsonBytes = BackupJson.encodeToString(writer.snapshot()).toByteArray(Charsets.UTF_8).size
        assertEquals(jsonBytes + pdfBytes, estimate)
    }

    @Test
    fun estimateIgnoresManualsWhosePdfIsMissing() = runTest {
        seedManual("manual-1", "Gen2 Service Manual", pdfOne)
        seedManual("manual-2", "Gen2 Installation Manual", null)

        val estimate = writer.estimateSizeBytes()

        val jsonBytes = BackupJson.encodeToString(writer.snapshot()).toByteArray(Charsets.UTF_8).size
        assertEquals(jsonBytes + pdfOne.size.toLong(), estimate)
    }

    @Test
    fun missingPdfThrowsAndWritesNothing() = runTest {
        seedManual("manual-1", "Gen2 Service Manual", pdfOne)
        seedManual("manual-2", "Gen2 Installation Manual", null)
        val out = ByteArrayOutputStream()

        try {
            writer.write(out)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            assertTrue(message, "manual-2" in message)
            assertTrue(message, "Gen2 Installation Manual" in message)
        }

        assertEquals(0, out.size())
    }

    @Test
    fun statusAndEmptyCauseAndFixAreWrittenToJson() = runTest {
        db.errorCodeDao().upsertAll(
            listOf(
                // Both fields left at their declared defaults.
                ErrorCodeEntity(
                    id = "ec-1",
                    modelId = "model-1",
                    manualId = "manual-1",
                    code = "E 12",
                    normalizedCode = "E12",
                    meaning = "Door fault",
                    pageNumber = 7,
                    updatedAt = 4L,
                ),
            ),
        )
        seedManual("manual-1", "Gen2 Service Manual", pdfOne)
        val out = ByteArrayOutputStream()

        writer.write(out)

        val json = readZip(out.toByteArray()).bytes.getValue("backup.json").toString(Charsets.UTF_8)
        assertTrue(json, "\"SUGGESTED\"" in json)
        assertTrue(json, "\"causeAndFix\":\"\"" in json)
    }

    @Test
    fun leavesCallersStreamOpen() = runTest {
        seedManual("manual-1", "Gen2 Service Manual", pdfOne)
        val sink = ByteArrayOutputStream()
        var closed = false
        val out = object : OutputStream() {
            override fun write(b: Int) = sink.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = sink.write(b, off, len)
            override fun close() {
                closed = true
            }
        }

        writer.write(out)

        assertFalse("the caller owns the stream and closes it", closed)
        // The zip is complete (central directory written) even though the stream stays open.
        assertNotNull(readZip(sink.toByteArray()).bytes["backup.json"])
        assertEquals(2, readZip(sink.toByteArray()).names.size)
    }
}
