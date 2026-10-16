package com.elevman.ui.backup

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.backup.BACKUP_JSON_ENTRY
import com.elevman.backup.BACKUP_VERSION
import com.elevman.backup.BackupApplier
import com.elevman.backup.BackupDocument
import com.elevman.backup.BackupFiles
import com.elevman.backup.BackupReader
import com.elevman.backup.BackupWriter
import com.elevman.backup.CacheBackupFiles
import com.elevman.backup.MergePlan
import com.elevman.backup.MergePlanner
import com.elevman.backup.MergeTable
import com.elevman.backup.Overridden
import com.elevman.backup.ReadResult
import com.elevman.backup.Side
import com.elevman.backup.backupManualEntryName
import com.elevman.backup.toJsonBytes
import com.elevman.data.ElevManDatabase
import com.elevman.data.ElevatorModelEntity
import com.elevman.data.ManualEntity
import com.elevman.data.ManufacturerEntity
import com.elevman.domain.canMarkDone
import com.elevman.ui.theme.ElevManTheme
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A fake file picker and export folder: the view model never meets the Android picker or a provider. */
private class FakeFiles(private val pickedDir: File, private val exportsDir: File) : BackupFiles {
    /** The zip the "picker" hands over; null makes the copy fail, like a file that cannot be opened. */
    var source: File? = null
    val threads = ConcurrentLinkedQueue<Thread>()
    private var exports = 0

    override fun copyPicked(uri: Uri): File {
        threads += Thread.currentThread()
        val from = source ?: throw IOException("cannot open $uri")
        return File(pickedDir, UUID.randomUUID().toString() + ".zip").also { from.copyTo(it) }
    }

    override fun newExportFile(): File {
        threads += Thread.currentThread()
        exportsDir.mkdirs()
        return File(exportsDir, "backup-${exports++}.zip")
    }
}

// A tall screen, so that a whole preview is on screen: a lazy list only builds what is visible.
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h1200dp")
class BackupScreenTest {

    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: ElevManDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var filesDir: File
    private lateinit var stagingRoot: File
    private lateinit var pickedDir: File
    private lateinit var exportsDir: File
    private lateinit var fakeFiles: FakeFiles
    private lateinit var writer: BackupWriter
    private lateinit var reader: BackupReader
    private lateinit var applier: BackupApplier

    private val clock = Clock.fixed(Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC)
    private val picked = Uri.parse("content://test/backup.zip")

    private val pdf = "%PDF-1.7 service manual".toByteArray()
    private val maker = ManufacturerEntity("mk-1", "Otis", 10L)
    private val model = ElevatorModelEntity("model-1", "mk-1", "Gen2", "", 10L)
    private val manual = ManualEntity("man-1", "model-1", "Service manual", "hash-1", 3, 10L)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        filesDir = tempFolder.newFolder("files")
        stagingRoot = tempFolder.newFolder("staging")
        pickedDir = tempFolder.newFolder("picked")
        exportsDir = File(tempFolder.root, "exports")
        fakeFiles = FakeFiles(pickedDir, exportsDir)
        writer = BackupWriter(db, filesDir, clock)
        reader = BackupReader(stagingRoot)
        applier = BackupApplier(db, filesDir)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun viewModel(
        read: (File) -> ReadResult = reader::read,
        applier: BackupApplier = this.applier,
    ) = BackupViewModel(writer, applier, fakeFiles, read, scope)

    private fun show(viewModel: BackupViewModel) {
        rule.setContent {
            ElevManTheme {
                val state by viewModel.state.collectAsState()
                BackupScreen(
                    state = state,
                    onShare = viewModel::share,
                    // The picker returned a file.
                    onImport = { viewModel.importBackup(picked) },
                    onApply = viewModel::applyImport,
                    onCancel = viewModel::cancelImport,
                    onDismiss = viewModel::dismissNotice,
                )
            }
        }
    }

    private fun waitForText(text: String) =
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun BackupViewModel.settled(done: (BackupUiState) -> Boolean): BackupUiState =
        runBlocking { withTimeout(10.seconds) { state.first(done) } }

    private fun incoming() = BackupDocument(
        version = BACKUP_VERSION,
        exportedAt = 1_800_000_000_000L,
        manufacturers = listOf(maker),
        models = listOf(model),
        manuals = listOf(manual),
        errorCodes = emptyList(),
        maintenanceTasks = emptyList(),
        elevators = emptyList(),
        elevatorTasks = emptyList(),
        logEntries = emptyList(),
    )

    /** A good backup with one manual and its PDF, as the picker would hand it over. */
    private fun validBackup(): File {
        val file = tempFolder.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_JSON_ENTRY))
            zip.write(incoming().toJsonBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(backupManualEntryName(manual.id)))
            zip.write(pdf)
            zip.closeEntry()
        }
        return file
    }

    private fun stagedFolders(): List<String> = stagingRoot.list()!!.toList()

    // ---- BackupPreview -------------------------------------------------------------------------

    private fun showPreview(plan: MergePlan, onApply: () -> Unit = {}, onCancel: () -> Unit = {}) {
        rule.setContent { ElevManTheme { BackupPreview(plan, onApply, onCancel) } }
    }

    @Test
    fun previewShowsCountsAndOverriddenRecords() {
        val plan = MergePlan(
            added = 1,
            updated = 1,
            overridden = listOf(Overridden(MergeTable.MANUALS, "man-1", "Service manual", Side.LOCAL)),
        )

        showPreview(plan)

        rule.onNodeWithText("1 added").assertIsDisplayed()
        rule.onNodeWithText("1 updated").assertIsDisplayed()
        rule.onNodeWithText("1 kept your version").assertIsDisplayed()
        rule.onNodeWithText("Service manual").assertIsDisplayed()
    }

    @Test
    fun previewShowsTheWinnerOfEachOverriddenRecord() {
        // Built by the planner, so the preview is checked against what the merge really reports.
        val local = incoming().copy(
            manuals = listOf(manual.copy(title = "Local newer", updatedAt = 50L), manual.copy(id = "man-2", title = "Local older", updatedAt = 5L)),
            models = emptyList(),
            manufacturers = emptyList(),
        )
        val theirs = incoming().copy(
            manuals = listOf(manual.copy(title = "Backup older", updatedAt = 20L), manual.copy(id = "man-2", title = "Backup newer", updatedAt = 30L)),
            models = listOf(model),
            manufacturers = listOf(maker),
        )
        val plan = MergePlanner.plan(local, theirs)

        showPreview(plan)

        // One record per side won, and each is listed with its winner. The listed label is the one that lost.
        rule.onNodeWithText("2 added").assertIsDisplayed()
        rule.onNodeWithText("1 updated").assertIsDisplayed()
        rule.onNodeWithText("1 kept your version").assertIsDisplayed()
        rule.onNodeWithText("Backup older").assertIsDisplayed()
        rule.onNodeWithText("Winner: your version").assertIsDisplayed()
        rule.onNodeWithText("Local older").assertIsDisplayed()
        rule.onNodeWithText("Winner: the backup").assertIsDisplayed()
    }

    @Test
    fun previewWithNoConflictsListsNoRecords() {
        showPreview(MergePlan(added = 3, updated = 0, overridden = emptyList()))

        rule.onNodeWithText("3 added").assertIsDisplayed()
        rule.onNodeWithText("0 updated").assertIsDisplayed()
        rule.onNodeWithText("0 kept your version").assertIsDisplayed()
        rule.onAllNodesWithText("Winner:", substring = true).assertCountEquals(0)
    }

    @Test
    fun applyAndCancelAreSeparateButtons() {
        val taps = mutableListOf<String>()
        showPreview(MergePlan(1, 0, emptyList()), onApply = { taps += "apply" }, onCancel = { taps += "cancel" })

        rule.onNodeWithText("Apply").performClick()
        rule.onNodeWithText("Cancel").performClick()

        assertEquals(listOf("apply", "cancel"), taps)
    }

    // ---- Mark done: the rule ------------------------------------------------------------------

    @Test
    fun canMarkDoneIsTrueForTodayAndFalseForTomorrow() {
        val today = LocalDate.of(2026, 10, 16)

        assertTrue(canMarkDone(today, today))
        assertFalse(canMarkDone(today.plusDays(1), today))
        // A backdated entry is fine; a date a year ahead is not.
        assertTrue(canMarkDone(today.minusDays(1), today))
        assertFalse(canMarkDone(today.plusYears(1), today))
    }

    // ---- Export: the size ---------------------------------------------------------------------

    @Test
    fun exportSizeIsAboutNMbWithNRoundedFromTheBytes() {
        assertEquals("Backup is about 240 MB", backupSizeText(240_000_000L))
        assertEquals("Backup is about 1 MB", backupSizeText(1_499_999L))
        assertEquals("Backup is about 2 MB", backupSizeText(1_500_000L))
        assertEquals("Backup is about 0 MB", backupSizeText(499_999L))
        assertEquals("Backup is about 0 MB", backupSizeText(0L))
    }

    @Test
    fun theSizeIsShownBeforeShareCanBePressed() {
        rule.setContent {
            ElevManTheme {
                BackupScreen(BackupUiState(estimating = true), {}, {}, {}, {}, {})
            }
        }

        rule.onNodeWithText("Share backup").assertIsNotEnabled()
        rule.onAllNodesWithText("Backup is about", substring = true).assertCountEquals(0)
    }

    @Test
    fun onceTheSizeIsKnownItIsShownAndShareIsEnabled() {
        val taps = mutableListOf<Unit>()
        rule.setContent {
            ElevManTheme {
                BackupScreen(BackupUiState(sizeBytes = 240_000_000L), { taps += Unit }, {}, {}, {}, {})
            }
        }

        rule.onNodeWithText("Backup is about 240 MB").assertIsDisplayed()
        rule.onNodeWithText("Share backup").assertIsEnabled().performClick()
        assertEquals(1, taps.size)
    }

    @Test
    fun theSizeComesFromTheWriterEstimate() {
        seedOneManualWithItsPdf()
        val viewModel = viewModel()

        viewModel.refreshSize()
        val state = viewModel.settled { it.sizeBytes != null }

        assertEquals(runBlocking { writer.estimateSizeBytes() }, state.sizeBytes)
        assertTrue(state.sizeBytes!! > pdf.size)
        assertFalse(state.estimating)
    }

    // ---- Export: writing and sharing ------------------------------------------------------------

    private fun seedOneManualWithItsPdf() = runBlocking {
        db.manufacturerDao().upsertAll(listOf(maker))
        db.elevatorModelDao().upsertAll(listOf(model))
        db.manualDao().upsertAll(listOf(manual))
        File(filesDir, backupManualEntryName(manual.id)).also { it.parentFile!!.mkdirs() }.writeBytes(pdf)
    }

    @Test
    fun shareWritesAReadableZipToTheExportsFolder() {
        seedOneManualWithItsPdf()
        val viewModel = viewModel()

        viewModel.share()
        val state = viewModel.settled { it.shareFile != null }

        val zip = state.shareFile!!
        assertEquals(exportsDir, zip.parentFile)
        assertFalse(state.writing)
        assertNull(state.exportError)
        // What was written is a backup the reader accepts, with the manual and its PDF.
        val read = reader.read(zip) as ReadResult.Valid
        assertEquals(listOf(manual), read.document.manuals)
        assertTrue(File(read.stagingDir, backupManualEntryName(manual.id)).readBytes().contentEquals(pdf))
    }

    @Test
    fun aFailedWriteDeletesThePartialFileAndShowsTheMessage() {
        // A manual row whose PDF is missing makes the writer refuse.
        runBlocking { db.manualDao().upsertAll(listOf(manual)) }
        val viewModel = viewModel()
        show(viewModel)

        viewModel.share()
        waitForText("The backup could not be written.")

        val state = viewModel.state.value
        assertNull(state.shareFile)
        assertFalse(state.writing)
        assertEquals("The backup could not be written.", state.exportError)
        assertEquals(emptyList<String>(), exportsDir.list()!!.toList())
    }

    @Test
    fun theShareFileIsHandedOverOnceAndKeptOnDisk() {
        seedOneManualWithItsPdf()
        val viewModel = viewModel()
        viewModel.share()
        val zip = viewModel.settled { it.shareFile != null }.shareFile!!

        viewModel.shareHandled()

        assertNull(viewModel.state.value.shareFile)
        // The receiving app reads it after the share sheet opens, so it stays.
        assertTrue(zip.isFile)
    }

    @Test
    fun aShareSheetThatCannotOpenDeletesTheFileAndSaysSo() {
        seedOneManualWithItsPdf()
        val viewModel = viewModel()
        viewModel.share()
        val zip = viewModel.settled { it.shareFile != null }.shareFile!!

        viewModel.shareFailed()

        assertNull(viewModel.state.value.shareFile)
        assertEquals("The backup could not be shared.", viewModel.state.value.exportError)
        waitUntilDeleted(zip)
    }

    /** Runs what the IO threads posted to the main thread, until [condition] holds. For a scope on the main thread. */
    private fun idleMainLooperUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("the condition was not met in time", condition())
    }

    private fun waitUntilDeleted(file: File) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (file.exists() && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse("$file is still there", file.exists())
    }

    private fun waitUntilEmpty(dir: File) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (dir.list()!!.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    // ---- The real files: the export folder and the copy of a picked file ------------------------

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aNewExportFileIsUnderExportsAndOlderExportsAreRemoved() {
        val cacheDir = tempFolder.newFolder("cache")
        val files = CacheBackupFiles(context.contentResolver, cacheDir, clock)
        val first = files.newExportFile().also { it.writeText("an earlier export") }
        File(first.parentFile, "stray.zip").writeText("another one")

        val second = files.newExportFile()

        assertEquals(File(cacheDir, "exports"), second.parentFile)
        assertEquals("elevman-backup-20270115-080000.zip", second.name)
        // Everything older is gone, and the new file is not created until it is written.
        assertEquals(emptyList<String>(), second.parentFile!!.list()!!.toList())
    }

    @Test
    fun aPickedFileIsCopiedIntoTheCache() {
        val cacheDir = tempFolder.newFolder("cache")
        val bytes = ByteArray(10_000) { (it % 251).toByte() }
        val uri = Uri.parse("content://test/good.zip")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))

        val copy = CacheBackupFiles(context.contentResolver, cacheDir, clock).copyPicked(uri)

        assertTrue(copy.readBytes().contentEquals(bytes))
        assertEquals(File(cacheDir, "backup-picked"), copy.parentFile)
    }

    @Test
    fun aPickedFileThatBreaksHalfwayLeavesNoCopy() {
        val cacheDir = tempFolder.newFolder("cache")
        val uri = Uri.parse("content://test/broken.zip")
        val broken = object : InputStream() {
            private var sent = 0
            override fun read(): Int = if (sent++ < 100) 1 else throw IOException("the stream broke")
        }
        shadowOf(context.contentResolver).registerInputStream(uri, broken)

        try {
            CacheBackupFiles(context.contentResolver, cacheDir, clock).copyPicked(uri)
            fail("the copy of a broken stream must fail")
        } catch (expected: IOException) {
            assertEquals("the stream broke", expected.message)
        }

        assertEquals(emptyList<String>(), File(cacheDir, "backup-picked").list()!!.toList())
    }

    // ---- Import: the reader's answer ----------------------------------------------------------

    @Test
    fun anInvalidResultShowsTheReadersReason() {
        val reason = "The PDF for Service manual is missing from the backup."
        fakeFiles.source = validBackup()
        val viewModel = viewModel(read = { ReadResult.Invalid(reason) })
        show(viewModel)

        rule.onNodeWithText("Import backup").performClick()
        waitForText(reason)

        rule.onNodeWithText(reason).assertIsDisplayed()
        // Nothing was staged, and the copy of the picked file is gone.
        assertEquals(emptyList<String>(), stagedFolders())
        assertEquals(emptyList<String>(), pickedDir.list()!!.toList())
    }

    @Test
    fun aFileThatIsNotAZipShowsTheRealReadersReason() {
        fakeFiles.source = tempFolder.newFile().apply { writeText("this is not a zip") }
        val viewModel = viewModel()
        show(viewModel)

        rule.onNodeWithText("Import backup").performClick()
        waitForText("This is not a backup file.")

        assertTrue(viewModel.state.value.importStep is ImportStep.Notice)
        assertEquals(emptyList<String>(), stagedFolders())
    }

    @Test
    fun aReadThatThrowsShowsTheGenericMessage() {
        fakeFiles.source = validBackup()
        val viewModel = viewModel(read = { throw IllegalArgumentException("duplicate entry: a.pdf/x") })
        show(viewModel)

        rule.onNodeWithText("Import backup").performClick()
        waitForText("This backup could not be opened.")

        rule.onNodeWithText("This backup could not be opened.").assertIsDisplayed()
        rule.onAllNodesWithText("duplicate entry", substring = true).assertCountEquals(0)
        // Nothing else happens: no preview, no apply.
        rule.onAllNodesWithText("Apply").assertCountEquals(0)
        assertEquals(emptyList<String>(), pickedDir.list()!!.toList())
    }

    @Test
    fun anyKindOfExceptionFromTheReadGivesTheGenericMessage() {
        fakeFiles.source = validBackup()
        val failures = listOf<Exception>(
            IOException("disk full"),
            RuntimeException("boom"),
            IllegalStateException("odd state"),
            SecurityException("no permission"),
            UnsupportedOperationException(),
        )
        for (failure in failures) {
            val viewModel = viewModel(read = { throw failure })

            viewModel.importBackup(picked)
            val step = viewModel.settled { it.importStep is ImportStep.Notice }.importStep

            assertEquals(failure.toString(), ImportStep.Notice("This backup could not be opened."), step)
        }
        assertEquals(emptyList<String>(), pickedDir.list()!!.toList())
    }

    @Test
    fun aPickedFileThatCannotBeCopiedShowsTheGenericMessage() {
        fakeFiles.source = null
        val viewModel = viewModel()

        viewModel.importBackup(picked)
        val step = viewModel.settled { it.importStep is ImportStep.Notice }.importStep

        assertEquals(ImportStep.Notice("This backup could not be opened."), step)
    }

    @Test
    fun theNoticeCanBeClosedAndANewBackupPicked() {
        fakeFiles.source = validBackup()
        val viewModel = viewModel(read = { throw IOException("x") })
        show(viewModel)
        rule.onNodeWithText("Import backup").performClick()
        waitForText("This backup could not be opened.")

        rule.onNodeWithText("Close").performClick()

        rule.onAllNodesWithText("This backup could not be opened.").assertCountEquals(0)
        rule.onNodeWithText("Import backup").assertIsEnabled()
    }

    // ---- Import: preview, apply, cancel and the staging folder ----------------------------------

    @Test
    fun aValidBackupShowsThePreviewOfItsPlan() {
        fakeFiles.source = validBackup()
        val viewModel = viewModel()
        show(viewModel)

        rule.onNodeWithText("Import backup").performClick()
        waitForText("Apply")

        // Maker, model and manual are all new on an empty phone.
        rule.onNodeWithText("3 added").assertIsDisplayed()
        rule.onNodeWithText("0 updated").assertIsDisplayed()
        assertEquals(1, stagedFolders().size)
        // The copy of the picked file is not needed once its PDFs are staged.
        assertEquals(emptyList<String>(), pickedDir.list()!!.toList())
    }

    @Test
    fun stagingIsDeletedAfterACancel() {
        fakeFiles.source = validBackup()
        val viewModel = viewModel()
        show(viewModel)
        rule.onNodeWithText("Import backup").performClick()
        waitForText("Apply")
        assertEquals(1, stagedFolders().size)

        rule.onNodeWithText("Cancel").performClick()

        waitUntilEmpty(stagingRoot)
        rule.onAllNodesWithText("Apply").assertCountEquals(0)
        assertEquals(ImportStep.Idle, viewModel.state.value.importStep)
        // Cancelling changed nothing.
        assertEquals(emptyList<ManufacturerEntity>(), runBlocking { db.manufacturerDao().getAll() })
        assertFalse(File(filesDir, backupManualEntryName(manual.id)).exists())
    }

    @Test
    fun applyingImportsTheBackupAndDeletesStaging() {
        fakeFiles.source = validBackup()
        val viewModel = viewModel()
        show(viewModel)
        rule.onNodeWithText("Import backup").performClick()
        waitForText("Apply")

        rule.onNodeWithText("Apply").performClick()
        waitForText("Backup imported.")

        assertEquals(listOf(maker), runBlocking { db.manufacturerDao().getAll() })
        assertEquals(listOf(manual), runBlocking { db.manualDao().getAll() })
        assertTrue(File(filesDir, backupManualEntryName(manual.id)).readBytes().contentEquals(pdf))
        waitUntilEmpty(stagingRoot)
    }

    @Test
    fun anApplyThatFailsWithIllegalStateShowsItsMessageAndDeletesStaging() {
        fakeFiles.source = validBackup()
        val viewModel = viewModel()
        show(viewModel)
        rule.onNodeWithText("Import backup").performClick()
        waitForText("Apply")
        // The local data changes after the preview: the applier refuses a stale plan.
        runBlocking { db.manufacturerDao().upsertAll(listOf(maker.copy(name = "Otis Elevators", updatedAt = 99L))) }

        rule.onNodeWithText("Apply").performClick()
        waitForText("The local data changed since the preview. Preview the backup again.")

        waitUntilEmpty(stagingRoot)
        // Nothing of the backup was applied.
        assertEquals(emptyList<ManualEntity>(), runBlocking { db.manualDao().getAll() })
    }

    @Test
    fun anApplyThatFailsOtherwiseShowsAGenericMessageAndDeletesStaging() {
        fakeFiles.source = validBackup()
        val failing = BackupApplier(db, filesDir, onWrite = { throw IOException("disk full") })
        val viewModel = viewModel(applier = failing)
        show(viewModel)
        rule.onNodeWithText("Import backup").performClick()
        waitForText("Apply")

        rule.onNodeWithText("Apply").performClick()
        waitForText("The backup could not be imported.")

        waitUntilEmpty(stagingRoot)
        assertEquals(emptyList<ManualEntity>(), runBlocking { db.manualDao().getAll() })
    }

    @Test
    fun stagingIsDeletedWhenTheViewModelIsClearedWhileAPreviewIsWaiting() {
        fakeFiles.source = validBackup()
        val store = ViewModelStore()
        val viewModel = viewModel()
        val provided = ViewModelProvider.create(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = viewModel as T
            },
        )[BackupViewModel::class]
        provided.importBackup(picked)
        provided.settled { it.importStep is ImportStep.Preview }
        assertEquals(1, stagedFolders().size)

        store.clear()

        waitUntilEmpty(stagingRoot)
    }

    @Test
    fun aSecondPickWhileAPreviewIsWaitingIsIgnored() {
        fakeFiles.source = validBackup()
        val viewModel = viewModel()
        viewModel.importBackup(picked)
        viewModel.settled { it.importStep is ImportStep.Preview }

        viewModel.importBackup(picked)

        // Still the one preview: the picked file was not copied a second time, and nothing more was staged.
        assertTrue(viewModel.state.value.importStep is ImportStep.Preview)
        assertEquals(1, fakeFiles.threads.size)
        assertEquals(1, stagedFolders().size)
    }

    // ---- Off the main thread ------------------------------------------------------------------

    @Test
    fun readingCopyingAndWritingRunOffTheMainThread() {
        seedOneManualWithItsPdf()
        fakeFiles.source = validBackup()
        val readThreads = ConcurrentLinkedQueue<Thread>()
        // The scope the app uses: the view model starts on the main thread, so only its own switch
        // to the IO dispatcher can keep the file work off it.
        val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val viewModel = BackupViewModel(
                writer = writer,
                applier = applier,
                files = fakeFiles,
                readBackup = { zip ->
                    readThreads += Thread.currentThread()
                    reader.read(zip)
                },
                scope = mainScope,
            )

            viewModel.refreshSize()
            idleMainLooperUntil { viewModel.state.value.sizeBytes != null }
            viewModel.share()
            idleMainLooperUntil { viewModel.state.value.shareFile != null }
            viewModel.importBackup(picked)
            idleMainLooperUntil { viewModel.state.value.importStep is ImportStep.Preview }
        } finally {
            mainScope.cancel()
        }

        val main = Looper.getMainLooper().thread
        assertTrue("the picked file was copied", fakeFiles.threads.size >= 2)
        assertTrue("the zip was read", readThreads.isNotEmpty())
        assertTrue("copy, export file and read all ran off the main thread", (fakeFiles.threads + readThreads).none { it === main })
    }

    // ---- The FileProvider ----------------------------------------------------------------------

    @Test
    fun anExportedFileGetsAContentUriFromTheAppsFileProvider() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(File(context.cacheDir, "exports"), "backup.zip").also {
            it.parentFile!!.mkdirs()
            it.writeText("zip")
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        assertEquals("content", uri.scheme)
        assertEquals("com.elevman.fileprovider", uri.authority)
        assertEquals("/exports/backup.zip", uri.path)
    }

    @Test
    fun theFileProviderServesNothingOutsideTheExportsFolder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val other = File(File(context.cacheDir, "backup-staging"), "secret.pdf").also {
            it.parentFile!!.mkdirs()
            it.writeText("pdf")
        }

        try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", other)
            fail("a file outside cache/exports must not get a URI")
        } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }
}
