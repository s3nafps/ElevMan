package com.elevman.ui.library

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.data.ElevManDatabase
import com.elevman.data.ElevatorModelEntity
import com.elevman.data.ManualImporter
import com.elevman.data.ManualStore
import com.elevman.data.ManufacturerEntity
import com.elevman.pdf.PageText
import com.elevman.pdf.PageTextSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
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
    override fun open(uri: Uri): InputStream? = files[uri]?.let { ByteArrayInputStream(it) }
    override fun displayName(uri: Uri): String? = names[uri]
}

private class FakePageText(var pages: List<PageText> = emptyList(), var failure: Throwable? = null) : PageTextSource {
    var ranOnMainThread: Boolean? = null
    override suspend fun pages(file: File): List<PageText> {
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
    private lateinit var viewModel: ImportViewModel

    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
    private val manualIds = ArrayDeque((1..9).map { "manual-$it" })
    private val otherIds = ArrayDeque((1..9).map { "id-$it" })
    private val uri = Uri.parse("content://test/a.pdf")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pdfs = FakePdfSource(mutableMapOf(uri to "%PDF-1.7 first".toByteArray()))
        text = FakePageText(listOf(PageText(1, "E-21 Door zone fault\nCheck the door sensor")))
        val filesDir = tempFolder.newFolder("files")
        val importer = ManualImporter(
            store = ManualStore(filesDir, db.manualDao(), clock, newId = { manualIds.removeFirst() }),
            text = text,
            manuals = db.manualDao(),
            codes = db.errorCodeDao(),
            tasks = db.maintenanceTaskDao(),
            clock = clock,
            db = db,
        )
        viewModel = ImportViewModel(
            importer = importer,
            manufacturers = db.manufacturerDao(),
            models = db.elevatorModelDao(),
            manuals = db.manualDao(),
            pdfs = pdfs,
            clock = clock,
            newId = { otherIds.removeFirst() },
            scope = scope,
        )
    }

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

    // Reads the database from the test thread, where the in-memory database allows it.
    private fun <T> ElevManDatabase.runBlockingGet(block: suspend ElevManDatabase.() -> T): T =
        runBlocking { block() }
}
