package com.elevman.ui.library

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.data.ElevManDatabase
import com.elevman.data.ElevatorModelDao
import com.elevman.data.ElevatorModelEntity
import com.elevman.data.ManualImporter
import com.elevman.data.ManualStore
import com.elevman.data.ManufacturerDao
import com.elevman.data.ManufacturerEntity
import com.elevman.pdf.PageText
import com.elevman.pdf.PageTextSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Reads from a map of uri to bytes; a uri that is not in the map cannot be opened. */
private class FakePdfSource(val files: MutableMap<Uri, ByteArray> = mutableMapOf()) : PdfSource {
    var names = mutableMapOf<Uri, String>()
    val streams = mutableMapOf<Uri, () -> InputStream>()
    override fun open(uri: Uri): InputStream? = streams[uri]?.invoke() ?: files[uri]?.let { ByteArrayInputStream(it) }
    override fun displayName(uri: Uri): String? = names[uri]
}

/** A stream that fails when it is read, like a shared file whose permission has just ended. */
private class FailingStream : InputStream() {
    override fun read(): Int = throw IOException("the stream broke")
}

/** Notes every write to the model table, in order, and passes it on. */
private class RecordingModels(private val real: ElevatorModelDao, val events: MutableList<String>) : ElevatorModelDao by real {
    override suspend fun upsertAll(rows: List<ElevatorModelEntity>) {
        events += "save model " + rows.joinToString { it.name }
        real.upsertAll(rows)
    }

    override suspend fun deleteById(id: String) {
        events += "delete model $id"
        real.deleteById(id)
    }
}

/** Notes every write to the manufacturer table, in order, and passes it on. */
private class RecordingManufacturers(private val real: ManufacturerDao, val events: MutableList<String>) : ManufacturerDao by real {
    override suspend fun upsertAll(rows: List<ManufacturerEntity>) {
        events += "save maker " + rows.joinToString { it.name }
        real.upsertAll(rows)
    }

    override suspend fun deleteById(id: String) {
        events += "delete maker $id"
        real.deleteById(id)
    }
}

/** Shared by the manufacturer tables of several view models; while [latch] is set, they meet at it. */
private class Meeting {
    @Volatile
    var latch: CountDownLatch? = null
}

/**
 * Makes the reads of the manufacturer table wait for each other while the [meeting] is set, up to
 * [patienceMillis]: two imports that plan at the same moment both see an empty table. An import
 * that cannot plan until the other has finished just waits out the time and carries on.
 */
private class MeetingManufacturers(
    private val real: ManufacturerDao,
    private val meeting: Meeting,
    private val patienceMillis: Long,
) : ManufacturerDao by real {
    override suspend fun getAll(): List<ManufacturerEntity> {
        val rows = real.getAll()
        meeting.latch?.let {
            it.countDown()
            it.await(patienceMillis, TimeUnit.MILLISECONDS)
        }
        return rows
    }
}

private class FakePageText(var pages: List<PageText> = emptyList(), var failure: Throwable? = null) : PageTextSource {
    var ranOnMainThread: Boolean? = null
    var onPages: () -> Unit = {}
    override suspend fun pages(file: File): List<PageText> {
        onPages()
        ranOnMainThread = Thread.currentThread() == Looper.getMainLooper().thread
        failure?.let { throw it }
        return pages
    }
}

/**
 * Runs the view model on real threads against an in-memory database and the real importer, because
 * the import is meant to run on [Dispatchers.IO]. Every wait has a timeout.
 */
@RunWith(AndroidJUnit4::class)
class ImportViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: ElevManDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var pdfs: FakePdfSource
    private lateinit var text: FakePageText
    private lateinit var importer: ManualImporter
    private lateinit var viewModel: ImportViewModel

    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
    private val manualIds = ConcurrentLinkedDeque((1..9).map { "manual-$it" })
    private val otherIds = ConcurrentLinkedDeque((1..9).map { "id-$it" })
    private val uri = Uri.parse("content://test/a.pdf")

    // One lock for every view model of a test, as the app has one for all its imports.
    private val importLock = Mutex()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pdfs = FakePdfSource(mutableMapOf(uri to "%PDF-1.7 first".toByteArray()))
        text = FakePageText(listOf(PageText(1, "E-21 Door zone fault\nCheck the door sensor")))
        val filesDir = tempFolder.newFolder("files")
        importer = ManualImporter(
            store = ManualStore(filesDir, db.manualDao(), clock, newId = { manualIds.pollFirst()!! }),
            text = text,
            manuals = db.manualDao(),
            codes = db.errorCodeDao(),
            tasks = db.maintenanceTaskDao(),
            clock = clock,
            db = db,
        )
        viewModel = newViewModel()
    }

    private fun newViewModel(
        manufacturers: ManufacturerDao = db.manufacturerDao(),
        models: ElevatorModelDao = db.elevatorModelDao(),
        newId: () -> String = { otherIds.pollFirst()!! },
    ) = ImportViewModel(
        importer = importer,
        manufacturers = manufacturers,
        models = models,
        manuals = db.manualDao(),
        pdfs = pdfs,
        importLock = importLock,
        clock = clock,
        newId = newId,
        scope = scope,
    )

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun <T> wait(block: suspend () -> T): T = runBlocking { withTimeout(10.seconds) { block() } }

    private fun started(): ImportUiState {
        viewModel.start(uri)
        return wait { viewModel.state.first { it.pick != ModelPick.None || it.choices.isNotEmpty() || it.title.isNotEmpty() } }
    }

    private fun fillNewModel(title: String = "Gen2 Service Manual") {
        viewModel.setTitle(title)
        viewModel.pickNew()
        viewModel.setNewManufacturer("Otis")
        viewModel.setNewModel("Gen2")
    }

    private fun finishedState(): ImportUiState =
        wait { viewModel.state.first { !it.working && (it.notice != null || it.finished != null) } }

    @Test
    fun anImportedManualFinishesWithItsModelAndManual() {
        started()
        fillNewModel()

        viewModel.import()
        val done = finishedState()

        val finished = done.finished!!
        assertEquals("manual-1", finished.manualId)
        assertNull(done.notice)
        // The new model and its manufacturer were saved, and the manual points at the model.
        val model = db.runBlockingGet { elevatorModelDao().getAll() }.single()
        assertEquals(finished.modelId, model.id)
        assertEquals("Gen2", model.name)
        assertEquals("Otis", db.runBlockingGet { manufacturerDao().getAll() }.single().name)
        assertEquals(finished.modelId, db.runBlockingGet { manualDao().getAll() }.single().modelId)
        assertEquals("Gen2 Service Manual", db.runBlockingGet { manualDao().getAll() }.single().title)
    }

    @Test
    fun theImporterRunsOffTheMainThread() {
        started()
        fillNewModel()

        viewModel.import()
        finishedState()

        assertEquals(false, text.ranOnMainThread)
    }

    @Test
    fun aPdfWithNoTextGivesTheNoTextMessageAndKeepsTheManual() {
        text.pages = listOf(PageText(1, ""))
        started()
        fillNewModel()

        viewModel.import()
        val notice = finishedState().notice

        assertEquals("No text found in this PDF. Enter codes by hand.", notice?.message)
        // The manual is stored, so the model it is under exists and "Open in library" can open it.
        val model = db.runBlockingGet { elevatorModelDao().getAll() }.single()
        assertEquals(model.id, notice?.modelId)
        assertEquals(1, db.runBlockingGet { manualDao().getAll() }.size)
    }

    @Test
    fun aPdfThatCannotBeParsedGivesTheUnreadableMessage() {
        text.failure = IOException("bad xref")
        started()
        fillNewModel()

        viewModel.import()
        val notice = finishedState().notice

        assertEquals("This PDF could not be read.", notice?.message)
        assertTrue(notice is ImportNotice.Unreadable)
    }

    @Test
    fun aPdfThatCannotBeOpenedGivesTheUnreadableMessageAndStoresNothing() {
        pdfs.files.clear()
        started()
        fillNewModel()

        viewModel.import()
        val notice = finishedState().notice

        assertEquals("This PDF could not be read.", notice?.message)
        assertEquals(emptyList<Any>(), db.runBlockingGet { manualDao().getAll() })
        assertEquals(emptyList<Any>(), db.runBlockingGet { elevatorModelDao().getAll() })
    }

    @Test
    fun aDuplicateNamesTheExistingTitleAndLeavesNoNewModelBehind() {
        started()
        fillNewModel(title = "Gen2 Service Manual")
        viewModel.import()
        val first = finishedState().finished!!
        viewModel.reset()

        // The same bytes again, under a new model and a new title.
        viewModel.start(uri)
        wait { viewModel.state.first { it.choices.isNotEmpty() } }
        viewModel.setTitle("Another title")
        viewModel.pickNew()
        viewModel.setNewManufacturer("Schindler")
        viewModel.setNewModel("3300")
        viewModel.import()
        val notice = finishedState().notice

        assertEquals("Already in your library as Gen2 Service Manual.", notice?.message)
        // "Open in library" opens the model of the manual that is already there.
        assertEquals(first.modelId, notice?.modelId)
        assertEquals(listOf("Gen2"), db.runBlockingGet { elevatorModelDao().getAll() }.map { it.name })
        assertEquals(listOf("Otis"), db.runBlockingGet { manufacturerDao().getAll() }.map { it.name })
    }

    @Test
    fun anExistingModelIsUsedAsItIs() {
        db.runBlockingGet { manufacturerDao().upsertAll(listOf(ManufacturerEntity("mk", "Otis", 1L))) }
        db.runBlockingGet { elevatorModelDao().upsertAll(listOf(ElevatorModelEntity("mod", "mk", "Gen2", "", 1L))) }
        viewModel.start(uri)
        wait { viewModel.state.first { it.choices.isNotEmpty() } }
        viewModel.setTitle("Manual")
        viewModel.pickExisting("mod")

        viewModel.import()
        val finished = finishedState().finished

        assertEquals("mod", finished?.modelId)
        assertEquals(1, db.runBlockingGet { elevatorModelDao().getAll() }.size)
        assertEquals("mod", db.runBlockingGet { manualDao().getAll() }.single().modelId)
    }

    @Test
    fun aNewModelWithAKnownNameReusesTheManufacturerAndModel() {
        db.runBlockingGet { manufacturerDao().upsertAll(listOf(ManufacturerEntity("mk", "Otis", 1L))) }
        db.runBlockingGet { elevatorModelDao().upsertAll(listOf(ElevatorModelEntity("mod", "mk", "Gen2", "", 1L))) }
        viewModel.start(uri)
        wait { viewModel.state.first { it.choices.isNotEmpty() } }
        viewModel.setTitle("Manual")
        viewModel.pickNew()
        viewModel.setNewManufacturer("  otis ")
        viewModel.setNewModel("GEN2")

        viewModel.import()
        val finished = finishedState().finished

        assertEquals("mod", finished?.modelId)
        assertEquals(1, db.runBlockingGet { manufacturerDao().getAll() }.size)
        assertEquals(1, db.runBlockingGet { elevatorModelDao().getAll() }.size)
    }

    @Test
    fun theFormNeedsATitleAndAModelBeforeItCanImport() {
        started()
        assertFalse(viewModel.state.value.canImport)

        viewModel.setTitle("Manual")
        assertFalse(viewModel.state.value.canImport)

        viewModel.pickNew()
        assertFalse(viewModel.state.value.canImport)
        viewModel.setNewManufacturer("Otis")
        assertFalse(viewModel.state.value.canImport)
        viewModel.setNewModel("Gen2")
        assertTrue(viewModel.state.value.canImport)

        viewModel.setTitle("   ")
        assertFalse(viewModel.state.value.canImport)
    }

    @Test
    fun importingWithAnIncompleteFormDoesNothing() {
        started()

        viewModel.import()

        assertFalse(viewModel.state.value.working)
        assertEquals(emptyList<Any>(), db.runBlockingGet { manualDao().getAll() })
    }

    @Test
    fun anEmptyLibraryStartsOnTheNewModelForm() {
        val state = started()

        assertEquals(ModelPick.New, state.pick)
    }

    @Test
    fun theFileNameBecomesTheSuggestedTitleWithoutItsExtension() {
        pdfs.names[uri] = "Gen2 service manual.pdf"

        val state = started()

        assertEquals("Gen2 service manual", state.title)
    }


    // ---- The model is saved before the import ------------------------------------------------

    @Test
    fun aNewModelIsKeptWhenTheImportFails() {
        val failingUri = Uri.parse("content://test/failing.pdf")
        pdfs.streams[failingUri] = { FailingStream() }
        viewModel.start(failingUri)
        wait { viewModel.state.first { it.pick == ModelPick.New } }
        fillNewModel()

        viewModel.import()
        val notice = finishedState().notice

        assertEquals("This PDF could not be imported. Try again.", notice?.message)
        assertTrue(notice is ImportNotice.Failed)
        // The model was saved first and stays: a manual that did get stored must have its model.
        assertEquals(listOf("Gen2"), db.runBlockingGet { elevatorModelDao().getAll() }.map { it.name })
        assertEquals(listOf("Otis"), db.runBlockingGet { manufacturerDao().getAll() }.map { it.name })
        assertEquals(emptyList<Any>(), db.runBlockingGet { manualDao().getAll() })
    }

    @Test
    fun theNewModelIsSavedBeforeTheImporterRunsAndKeptWhenTheManualHasNoText() {
        val events = mutableListOf<String>()
        text.pages = listOf(PageText(1, ""))
        text.onPages = { events += "import" }
        viewModel = newViewModel(
            manufacturers = RecordingManufacturers(db.manufacturerDao(), events),
            models = RecordingModels(db.elevatorModelDao(), events),
        )
        started()
        fillNewModel()

        viewModel.import()
        finishedState()

        assertEquals(listOf("save maker Otis", "save model Gen2", "import"), events)
        assertEquals(1, db.runBlockingGet { elevatorModelDao().getAll() }.size)
    }

    @Test
    fun aDuplicateRemovesTheNewEmptyModelAndManufacturerItJustSaved() {
        started()
        fillNewModel()
        viewModel.import()
        finishedState()
        viewModel.reset()

        // The same bytes again, under a manufacturer and model that do not exist yet.
        val events = mutableListOf<String>()
        viewModel = newViewModel(
            manufacturers = RecordingManufacturers(db.manufacturerDao(), events),
            models = RecordingModels(db.elevatorModelDao(), events),
        )
        viewModel.start(uri)
        wait { viewModel.state.first { it.choices.isNotEmpty() } }
        viewModel.setTitle("Another title")
        viewModel.pickNew()
        viewModel.setNewManufacturer("Schindler")
        viewModel.setNewModel("3300")
        viewModel.import()
        val notice = finishedState().notice

        assertTrue(notice is ImportNotice.Duplicate)
        // Saved first (the import cannot know it is a duplicate until it has the bytes), then removed again.
        assertEquals(
            listOf("save maker Schindler", "save model 3300", "delete model id-4", "delete maker id-3"),
            events,
        )
        assertEquals(listOf("Gen2"), db.runBlockingGet { elevatorModelDao().getAll() }.map { it.name })
        assertEquals(listOf("Otis"), db.runBlockingGet { manufacturerDao().getAll() }.map { it.name })
    }

    @Test
    fun aDuplicateKeepsAModelThatAlreadyExisted() {
        started()
        fillNewModel()
        viewModel.import()
        val first = finishedState().finished!!
        viewModel.reset()

        // The same bytes again, filed under the existing model: nothing new was made, so nothing may go.
        viewModel.start(uri)
        wait { viewModel.state.first { it.choices.isNotEmpty() } }
        viewModel.setTitle("Another title")
        viewModel.pickExisting(first.modelId)
        viewModel.import()
        val notice = finishedState().notice

        assertTrue(notice is ImportNotice.Duplicate)
        assertEquals(listOf("Gen2"), db.runBlockingGet { elevatorModelDao().getAll() }.map { it.name })
        assertEquals(listOf("Otis"), db.runBlockingGet { manufacturerDao().getAll() }.map { it.name })
    }

    @Test
    fun aDuplicateKeepsAKnownManufacturerAndRemovesOnlyTheNewModel() {
        started()
        fillNewModel()
        viewModel.import()
        finishedState()
        viewModel.reset()

        viewModel.start(uri)
        wait { viewModel.state.first { it.choices.isNotEmpty() } }
        viewModel.setTitle("Another title")
        viewModel.pickNew()
        viewModel.setNewManufacturer("OTIS")
        viewModel.setNewModel("Gen3")
        viewModel.import()
        finishedState()

        assertEquals(listOf("Gen2"), db.runBlockingGet { elevatorModelDao().getAll() }.map { it.name })
        assertEquals(listOf("Otis"), db.runBlockingGet { manufacturerDao().getAll() }.map { it.name })
    }

    // ---- A text reader that throws is an unreadable PDF --------------------------------------

    @Test
    fun aRuntimeExceptionFromTheTextReaderGivesUnreadableAndKeepsTheManual() {
        text.failure = IllegalStateException("the library choked")
        started()
        fillNewModel()

        viewModel.import()
        val notice = finishedState().notice

        assertEquals("This PDF could not be read.", notice?.message)
        assertTrue(notice is ImportNotice.Unreadable)
        val model = db.runBlockingGet { elevatorModelDao().getAll() }.single()
        assertEquals(model.id, notice?.modelId)
        // The PDF is stored and listed; only its text could not be read.
        assertEquals(model.id, db.runBlockingGet { manualDao().getAll() }.single().modelId)
    }

    // ---- Two imports at once ------------------------------------------------------------------

    @Test
    fun twoImportsAtOnceDoNotBothCreateTheSameNewManufacturer() {
        val second = Uri.parse("content://test/b.pdf")
        pdfs.files[second] = "%PDF-1.7 second".toByteArray()
        val meeting = Meeting()
        val ids1 = ConcurrentLinkedDeque(listOf("a-1", "a-2"))
        val ids2 = ConcurrentLinkedDeque(listOf("b-1", "b-2"))
        val one = newViewModel(MeetingManufacturers(db.manufacturerDao(), meeting, 1_500), newId = { ids1.pollFirst()!! })
        val two = newViewModel(MeetingManufacturers(db.manufacturerDao(), meeting, 1_500), newId = { ids2.pollFirst()!! })
        one.start(uri)
        two.start(second)
        wait { one.state.first { it.pick == ModelPick.New } }
        wait { two.state.first { it.pick == ModelPick.New } }
        for (vm in listOf(one, two)) {
            vm.setTitle("Manual")
            vm.setNewManufacturer("Otis")
            vm.setNewModel("Gen2")
        }

        // Both plan their model at the same moment, before either has saved anything.
        meeting.latch = CountDownLatch(2)
        one.import()
        two.import()
        wait { one.state.first { !it.working && (it.notice != null || it.finished != null) } }
        wait { two.state.first { !it.working && (it.notice != null || it.finished != null) } }

        assertEquals(listOf("Otis"), db.runBlockingGet { manufacturerDao().getAll() }.map { it.name })
        assertEquals(listOf("Gen2"), db.runBlockingGet { elevatorModelDao().getAll() }.map { it.name })
        // Both manuals are under that one model.
        assertEquals(2, db.runBlockingGet { manualDao().getAll() }.size)
        assertEquals(1, db.runBlockingGet { manualDao().getAll() }.map { it.modelId }.toSet().size)
    }

    // Reads the database from the test thread, where the in-memory database allows it.
    private fun <T> ElevManDatabase.runBlockingGet(block: suspend ElevManDatabase.() -> T): T =
        runBlocking { block() }
}
