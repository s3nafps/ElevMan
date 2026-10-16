package com.elevman.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.elevman.backup.BackupApplier
import com.elevman.backup.BackupFiles
import com.elevman.backup.BackupReader
import com.elevman.backup.BackupWriter
import com.elevman.backup.MergePlan
import com.elevman.backup.MergePlanner
import com.elevman.backup.ReadResult
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val BACKUP_UNREADABLE_MESSAGE = "This backup could not be opened."
internal const val BACKUP_IMPORT_FAILED_MESSAGE = "The backup could not be imported."
internal const val BACKUP_IMPORTED_MESSAGE = "Backup imported."
internal const val BACKUP_WRITE_FAILED_MESSAGE = "The backup could not be written."
internal const val BACKUP_SHARE_FAILED_MESSAGE = "The backup could not be shared."
internal const val BACKUP_SIZE_FAILED_MESSAGE = "The size of the backup could not be worked out."

/** "Backup is about 240 MB": [bytes] in millions of bytes, rounded to the nearest whole number. */
internal fun backupSizeText(bytes: Long): String {
    val megabytes = bytes / 1_000_000L + if (bytes % 1_000_000L >= 500_000L) 1L else 0L
    return "Backup is about $megabytes MB"
}

/** Where an import is. Only [Preview] holds a staged backup. */
sealed interface ImportStep {
    data object Idle : ImportStep

    /** Copying the picked file and checking it. */
    data object Reading : ImportStep

    /** A valid backup, and what importing it would change. Waits for Apply or Cancel. */
    data class Preview(val plan: MergePlan) : ImportStep

    data object Applying : ImportStep

    /** The import ended, or could not start; [message] says how. */
    data class Notice(val message: String) : ImportStep
}

/**
 * What the Backup tab shows. [sizeBytes] is the estimate of the export; [shareFile] is a finished
 * export that is waiting to be handed to the share sheet.
 */
data class BackupUiState(
    val sizeBytes: Long? = null,
    val estimating: Boolean = false,
    val writing: Boolean = false,
    val exportError: String? = null,
    val shareFile: File? = null,
    val importStep: ImportStep = ImportStep.Idle,
)

/**
 * Runs the export and the import of a backup. Everything that touches the disk or the database (the
 * size estimate, writing the zip, copying and reading a picked file, the merge preview, the apply)
 * runs on [io] and never on the main thread.
 *
 * Export: [refreshSize] works out the size first, then [share] writes the zip to a new file from
 * [BackupFiles.newExportFile]. If the write fails the partial file is deleted. The finished file is
 * handed on in [BackupUiState.shareFile]; the screen opens the share sheet and calls [shareHandled].
 *
 * Import: [importBackup] copies the picked file, reads it with [readBackup] and, if it is valid,
 * previews the plan from [MergePlanner.plan] (local data against the backup). [applyImport] applies
 * that plan with [applier], which must be the app's single [BackupApplier]. The staging folder of a
 * valid backup is deleted on every way out: after the apply (whether it worked or not), after a cancel,
 * if the preview itself fails, and if this view model is cleared while a preview is waiting.
 *
 * [scope] ends when the view model is cleared. It defaults to the main thread.
 */
class BackupViewModel(
    private val writer: BackupWriter,
    private val applier: BackupApplier,
    private val files: BackupFiles,
    private val readBackup: (File) -> ReadResult,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(scope) {

    private val mutableState = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = mutableState.asStateFlow()

    /** The backup in the preview: the one read, and the plan made from it. */
    private class Pending(val valid: ReadResult.Valid, val plan: MergePlan)

    // Set on the IO thread that made the preview, taken on the main thread by apply, cancel or clear.
    private val pending = AtomicReference<Pending?>(null)

    @Volatile
    private var cleared = false

    // ---- Export --------------------------------------------------------------------------------

    /** Works out the size of the export again. Ignored while a size or a zip is already being worked on. */
    fun refreshSize() {
        val now = state.value
        if (now.estimating || now.writing) return
        mutableState.update { it.copy(estimating = true, exportError = null) }
        scope.launch {
            val bytes = try {
                withContext(io) { writer.estimateSizeBytes() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            mutableState.update {
                if (bytes != null) {
                    it.copy(estimating = false, sizeBytes = bytes)
                } else {
                    it.copy(estimating = false, sizeBytes = null, exportError = BACKUP_SIZE_FAILED_MESSAGE)
                }
            }
        }
    }

    /**
     * Writes the zip to a new file. Ignored while one is being written, and while an import is being
     * applied: an export reads every table and PDF, and an apply is replacing them, so the zip could
     * mix the two. The screen offers this only once the size is shown, so the person has seen how big
     * the file will be.
     */
    fun share() {
        val now = state.value
        if (now.writing || now.importStep is ImportStep.Applying) return
        mutableState.update { it.copy(writing = true, exportError = null) }
        scope.launch {
            // Not interrupted: a half-written zip would have to be cleaned up either way.
            val written = withContext(NonCancellable + io) { writeExport() }
            mutableState.update {
                it.copy(
                    writing = false,
                    shareFile = written,
                    exportError = if (written == null) BACKUP_WRITE_FAILED_MESSAGE else null,
                )
            }
        }
    }

    /** The finished export, or null if it could not be written. A partial file is deleted. */
    private suspend fun writeExport(): File? {
        val file = try {
            files.newExportFile()
        } catch (e: Exception) {
            return null
        }
        var written = false
        try {
            file.outputStream().use { writer.write(it) }
            written = true
            return file
        } catch (e: Exception) {
            return null
        } finally {
            if (!written) file.delete()
        }
    }

    /** The share sheet has been opened with the finished export. The file stays: the other app reads it later. */
    fun shareHandled() {
        mutableState.update { it.copy(shareFile = null) }
    }

    /** The share sheet could not be opened: the finished export is deleted. */
    fun shareFailed() {
        val file = state.value.shareFile
        mutableState.update { it.copy(shareFile = null, exportError = BACKUP_SHARE_FAILED_MESSAGE) }
        if (file != null) scope.launch { withContext(NonCancellable + io) { file.delete() } }
    }

    // ---- Import --------------------------------------------------------------------------------

    /**
     * Starts the import of the file the person picked. Ignored while an import is under way or
     * previewed, and while an export is being written.
     */
    fun importBackup(uri: Uri) {
        val step = state.value.importStep
        if (step is ImportStep.Reading || step is ImportStep.Preview || step is ImportStep.Applying) return
        if (state.value.writing) return
        mutableState.update { it.copy(importStep = ImportStep.Reading) }
        scope.launch {
            // Not interrupted: a backup that was read must reach the preview, or have its staging deleted here.
            val next = withContext(NonCancellable + io) { open(uri) }
            mutableState.update { it.copy(importStep = next) }
        }
    }

    /** Copies, reads and previews the picked file. Never throws. */
    private suspend fun open(uri: Uri): ImportStep {
        var copy: File? = null
        try {
            copy = files.copyPicked(uri)
            return when (val result = readBackup(copy)) {
                is ReadResult.Invalid -> ImportStep.Notice(result.reason)
                is ReadResult.Valid -> preview(result)
            }
        } catch (e: Exception) {
            // Any exception: a crafted file can make the reader throw something other than an IOException.
            return ImportStep.Notice(BACKUP_UNREADABLE_MESSAGE)
        } catch (e: OutOfMemoryError) {
            // A crafted backup.json can be far bigger than it looks compressed.
            return ImportStep.Notice(BACKUP_UNREADABLE_MESSAGE)
        } finally {
            // Its PDFs are staged by now (or the file was rejected), so the copy is not needed.
            copy?.delete()
        }
    }

    /** The preview of [valid]. Its staging folder is deleted unless the preview is reached. */
    private suspend fun preview(valid: ReadResult.Valid): ImportStep {
        var held = false
        try {
            val plan = MergePlanner.plan(writer.snapshot(), valid.document)
            hold(Pending(valid, plan))
            held = true
            return ImportStep.Preview(plan)
        } finally {
            if (!held) valid.stagingDir.deleteRecursively()
        }
    }

    private fun hold(pendingImport: Pending) {
        pending.set(pendingImport)
        // Cleared while the backup was being read: no screen will ever show this preview.
        if (cleared) discardPending()
    }

    private fun discardPending() {
        pending.getAndSet(null)?.valid?.stagingDir?.deleteRecursively()
    }

    /**
     * Applies the previewed backup. Does nothing unless a preview is showing, and nothing while an
     * export is being written (the preview waits).
     */
    fun applyImport() {
        if (state.value.importStep !is ImportStep.Preview || state.value.writing) return
        val held = pending.getAndSet(null) ?: return
        mutableState.update { it.copy(importStep = ImportStep.Applying) }
        scope.launch {
            val next = withContext(NonCancellable + io) { apply(held) }
            mutableState.update { it.copy(importStep = next) }
            // The data changed, or the attempt showed that it may have.
            refreshSize()
        }
    }

    /** Applies [held] and deletes its staging folder whatever happens. Never throws. */
    private suspend fun apply(held: Pending): ImportStep {
        try {
            applier.apply(held.valid, held.plan)
            return ImportStep.Notice(BACKUP_IMPORTED_MESSAGE)
        } catch (e: IllegalStateException) {
            // The applier's own words: the data changed since the preview, or an earlier import was cut short.
            return ImportStep.Notice(e.message?.takeIf { it.isNotBlank() } ?: BACKUP_IMPORT_FAILED_MESSAGE)
        } catch (e: Exception) {
            return ImportStep.Notice(BACKUP_IMPORT_FAILED_MESSAGE)
        } finally {
            held.valid.stagingDir.deleteRecursively()
        }
    }

    /**
     * Leaves the preview without importing anything, and deletes the staged backup. The backup is
     * taken here, on the main thread, and that very folder is what the IO side deletes. Looking the
     * pending backup up again later would delete the staging of an import started in the meantime.
     */
    fun cancelImport() {
        if (state.value.importStep !is ImportStep.Preview) return
        val cancelled = pending.getAndSet(null)
        mutableState.update { it.copy(importStep = ImportStep.Idle) }
        if (cancelled != null) {
            scope.launch { withContext(NonCancellable + io) { cancelled.valid.stagingDir.deleteRecursively() } }
        }
    }

    /** Closes the message an import ended with. */
    fun dismissNotice() {
        mutableState.update { if (it.importStep is ImportStep.Notice) it.copy(importStep = ImportStep.Idle) else it }
    }

    override fun onCleared() {
        cleared = true
        // A backup waiting in the preview is deleted. Not on the main thread, and not in [scope], which has ended.
        val waiting = pending.getAndSet(null) ?: return
        CoroutineScope(SupervisorJob() + io).launch { waiting.valid.stagingDir.deleteRecursively() }
    }

    companion object {
        /** [reader] is the reader of the container; its `read` is what turns a picked file into a [ReadResult]. */
        fun factory(writer: BackupWriter, reader: BackupReader, applier: BackupApplier, files: BackupFiles) =
            viewModelFactory {
                initializer { BackupViewModel(writer, applier, files, reader::read) }
            }
    }
}
