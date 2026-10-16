package com.elevman.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.pdf.PageText
import com.elevman.pdf.PageTextSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Returns [pages], or throws [failure] when it is set, and counts how often it is asked. */
private class FakePageTextSource(
    var pages: List<PageText> = emptyList(),
    var failure: Throwable? = null,
) : PageTextSource {
    val files = mutableListOf<File>()
    val calls: Int get() = files.size

    override suspend fun pages(file: File): List<PageText> {
        files += file
        failure?.let { throw it }
        return pages
    }
}

@RunWith(AndroidJUnit4::class)
class ManualImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: ElevManDatabase
    private lateinit var filesDir: File
    private lateinit var fake: FakePageTextSource
    private lateinit var importer: ManualImporter

    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
    private val ids = ArrayDeque(listOf("manual-1", "manual-2", "manual-3"))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesDir = tempFolder.newFolder("files")
        fake = FakePageTextSource()
        importer = ManualImporter(
            store = ManualStore(filesDir, db.manualDao(), clock, newId = { ids.removeFirst() }),
            text = fake,
            manuals = db.manualDao(),
            codes = db.errorCodeDao(),
            tasks = db.maintenanceTaskDao(),
            clock = clock,
            db = db,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun pdf(content: String) = ByteArrayInputStream("%PDF-1.7 $content".toByteArray())

    @Test
    fun importsSuggestionsAsSuggested() = runTest {
        fake.pages = listOf(
            PageText(1, "Introduction"),
            PageText(2, "E-21 Door zone fault\nCheck the door sensor"),
            PageText(3, "Quarterly lubricate the guide rails"),
        )

        val result = importer.import(pdf("manual"), "model-1", "Gen2 Service Manual")

        assertEquals(ImportResult.Imported("manual-1", suggestedCodes = 1, suggestedTasks = 1), result)
        assertEquals(1, fake.calls)
        assertEquals(File(File(filesDir, "manuals"), "manual-1.pdf"), fake.files.single())

        assertEquals(3, db.manualDao().getAll().single().pageCount)

        val code = db.errorCodeDao().getAll().single()
        assertEquals("E-21", code.code)
        assertEquals("E21", code.normalizedCode)
        assertEquals("Door zone fault Check the door sensor", code.meaning)
        assertEquals("", code.causeAndFix)
        assertEquals(2, code.pageNumber)
        assertEquals(ReviewStatus.SUGGESTED, code.status)
        assertEquals("model-1", code.modelId)
        assertEquals("manual-1", code.manualId)
        assertEquals(1_700_000_000_000L, code.updatedAt)

        val task = db.maintenanceTaskDao().getAll().single()
        assertEquals("Quarterly lubricate the guide rails", task.name)
        assertEquals(3, task.intervalMonths)
        assertEquals(3, task.pageNumber)
        assertEquals(ReviewStatus.SUGGESTED, task.status)
        assertEquals("model-1", task.modelId)
        assertEquals("manual-1", task.manualId)
        assertEquals(1_700_000_000_000L, task.updatedAt)
    }

    @Test
    fun everySuggestedRowGetsItsOwnId() = runTest {
        fake.pages = listOf(
            PageText(1, "E21 Door fault\n\nE22 Car overload\n\nMonthly check the doors\nYearly test the brake"),
        )

        val result = importer.import(pdf("many"), "model-1", "Manual")

        assertEquals(ImportResult.Imported("manual-1", suggestedCodes = 2, suggestedTasks = 2), result)
        val allIds = db.errorCodeDao().getAll().map { it.id } + db.maintenanceTaskDao().getAll().map { it.id }
        assertEquals(4, allIds.toSet().size)
    }

    @Test
    fun scannedManualKeepsManualWithNoSuggestions() = runTest {
        fake.pages = listOf(PageText(1, ""), PageText(2, "   \n"))

        val result = importer.import(pdf("scan"), "model-1", "Scanned Manual")

        assertEquals(ImportResult.NoText("manual-1"), result)
        val manual = db.manualDao().getAll().single()
        assertEquals("manual-1", manual.id)
        assertEquals(2, manual.pageCount)
        assertEquals(emptyList<ErrorCodeEntity>(), db.errorCodeDao().getAll())
        assertEquals(emptyList<MaintenanceTaskEntity>(), db.maintenanceTaskDao().getAll())
    }

    @Test
    fun duplicateSkipsExtraction() = runTest {
        fake.pages = listOf(PageText(1, "E21 Door fault"))
        importer.import(pdf("same"), "model-1", "Original Title")
        val callsAfterFirst = fake.calls

        val result = importer.import(pdf("same"), "model-2", "Other Title")

        assertEquals(ImportResult.Duplicate("manual-1", "Original Title"), result)
        assertEquals(1, callsAfterFirst)
        assertEquals(callsAfterFirst, fake.calls)
        assertEquals(1, db.manualDao().getAll().size)
        assertEquals(1, db.errorCodeDao().getAll().size)
    }

    @Test
    fun unreadablePdfKeepsManualWithNoSuggestions() = runTest {
        fake.failure = IOException("Missing root object specification in trailer.")

        val result = importer.import(pdf("broken"), "model-1", "Broken Manual")

        assertEquals(
            ImportResult.Unreadable("manual-1", "Missing root object specification in trailer."),
            result,
        )
        assertEquals(1, fake.calls)
        val manual = db.manualDao().getAll().single()
        assertEquals("manual-1", manual.id)
        assertEquals(0, manual.pageCount)
        assertEquals(emptyList<ErrorCodeEntity>(), db.errorCodeDao().getAll())
        assertEquals(emptyList<MaintenanceTaskEntity>(), db.maintenanceTaskDao().getAll())
    }

    @Test
    fun unreadableWithoutMessageReportsExceptionClassName() = runTest {
        fake.failure = IOException()

        val result = importer.import(pdf("broken"), "model-1", "Broken Manual")

        assertEquals(ImportResult.Unreadable("manual-1", "java.io.IOException"), result)
    }

    @Test
    fun anyExceptionFromTextSourceIsUnreadableAndKeepsTheManual() = runTest {
        // Not only IOException: a PDF the library chokes on can throw a RuntimeException, as the viewer knows.
        fake.failure = IllegalStateException("not an IO problem")

        val result = importer.import(pdf("odd"), "model-1", "Odd Manual")

        assertEquals(ImportResult.Unreadable("manual-1", "not an IO problem"), result)
        val manual = db.manualDao().getAll().single()
        assertEquals("manual-1", manual.id)
        assertEquals(0, manual.pageCount)
        assertTrue(db.errorCodeDao().getAll().isEmpty())
        assertTrue(File(File(filesDir, "manuals"), "manual-1.pdf").exists())
    }

    @Test
    fun aRuntimeExceptionWithoutMessageReportsItsClassName() = runTest {
        fake.failure = RuntimeException()

        val result = importer.import(pdf("odd"), "model-1", "Odd Manual")

        assertEquals(ImportResult.Unreadable("manual-1", "java.lang.RuntimeException"), result)
    }

    @Test
    fun cancellationFromTextSourceIsNotTurnedIntoUnreadable() = runTest {
        fake.failure = CancellationException("the screen was left")

        try {
            importer.import(pdf("cancelled"), "model-1", "Manual")
            fail("expected the cancellation to propagate")
        } catch (expected: CancellationException) {
            assertEquals("the screen was left", expected.message)
        }
    }

    @Test
    fun failedTaskInsertRollsBackTheSuggestedCodes() = runTest {
        val realTasks = db.maintenanceTaskDao()
        val failingTasks = object : MaintenanceTaskDao by realTasks {
            override suspend fun upsertAll(rows: List<MaintenanceTaskEntity>) {
                throw IllegalStateException("task insert failed")
            }
        }
        val failingImporter = ManualImporter(
            store = ManualStore(filesDir, db.manualDao(), clock, newId = { "manual-9" }),
            text = fake,
            manuals = db.manualDao(),
            codes = db.errorCodeDao(),
            tasks = failingTasks,
            clock = clock,
            db = db,
        )
        fake.pages = listOf(PageText(1, "E21 Door fault\n\nQuarterly check the doors"))

        try {
            failingImporter.import(pdf("rollback"), "model-1", "Manual")
            fail("expected the insert failure to propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("task insert failed", expected.message)
        }

        assertEquals(emptyList<ErrorCodeEntity>(), db.errorCodeDao().getAll())
        assertEquals(emptyList<MaintenanceTaskEntity>(), db.maintenanceTaskDao().getAll())
    }
}
