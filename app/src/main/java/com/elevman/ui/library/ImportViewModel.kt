package com.elevman.ui.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.elevman.data.ElevManDatabase
import com.elevman.data.ElevatorModelDao
import com.elevman.data.ElevatorModelEntity
import com.elevman.data.ImportResult
import com.elevman.data.ManualDao
import com.elevman.data.ManualImporter
import com.elevman.data.ManufacturerDao
import com.elevman.data.ManufacturerEntity
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.util.UUID
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

internal const val NO_TEXT_MESSAGE = "No text found in this PDF. Enter codes by hand."
internal const val UNREADABLE_MESSAGE = "This PDF could not be read."
internal const val IMPORT_FAILED_MESSAGE = "The PDF could not be imported. Check that the phone has free space, then try again."

/**
 * What the person is told when an import ends without a manual to review. An imported manual is
 * not a notice: it goes straight to the review.
 *
 * [modelId] is the model whose page lists the manual the notice is about, or null if that is not
 * known. It is what "Open in library" opens.
 */
sealed interface ImportNotice {
    val message: String
    val modelId: String?

    /** The manual was stored, but it has no text layer (a scan). */
    data class NoText(override val modelId: String?) : ImportNotice {
        override val message: String get() = NO_TEXT_MESSAGE
    }

    /** A PDF with the same bytes is already in the library. */
    data class Duplicate(val existingTitle: String, override val modelId: String?) : ImportNotice {
        override val message: String get() = "Already in your library as $existingTitle."
    }

    /** The PDF could not be opened, or was stored but could not be parsed. */
    data class Unreadable(override val modelId: String?) : ImportNotice {
        override val message: String get() = UNREADABLE_MESSAGE
    }

    /** The import stopped on an error (for example, no space left); nothing was stored. */
    data object Failed : ImportNotice {
        override val message: String get() = IMPORT_FAILED_MESSAGE
        override val modelId: String? get() = null
    }
}

/**
 * The notice for [this] result, or null for [ImportResult.Imported], which opens the review.
 * [modelId] is the model of the manual the result is about; for a duplicate, the model of the
 * manual that is already there.
 */
internal fun ImportResult.toNotice(modelId: String?): ImportNotice? = when (this) {
    is ImportResult.Imported -> null
    is ImportResult.NoText -> ImportNotice.NoText(modelId)
    is ImportResult.Duplicate -> ImportNotice.Duplicate(existingTitle, modelId)
    is ImportResult.Unreadable -> ImportNotice.Unreadable(modelId)
}

/** Opens the PDF the person picked or another app shared. */
interface PdfSource {
    /** The PDF's bytes, or null when it cannot be opened. The caller closes the stream. */
    fun open(uri: Uri): InputStream?

    /** The file name to offer as a title, or null if there is none. */
    fun displayName(uri: Uri): String?
}

internal class ContentResolverPdfSource(private val resolver: ContentResolver) : PdfSource {
    override fun open(uri: Uri): InputStream? = resolver.openInputStream(uri)

    override fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: RuntimeException) {
        // A title is only a convenience; a provider that refuses the query just gets no suggestion.
        null
    }
}

/** An existing model the PDF can be filed under. */
data class ModelChoice(val id: String, val label: String)

/** Which model the manual goes to. */
sealed interface ModelPick {
    data object None : ModelPick
    data class Existing(val modelId: String) : ModelPick
    data object New : ModelPick
}

/** Where the import ended up: the manual to review, and the model it belongs to. */
data class ImportFinished(val modelId: String, val manualId: String)

/**
 * The import form and its outcome. [canImport] is true when the title is filled in and a model is
 * chosen (an existing one, or a new one with both names).
 */
data class ImportUiState(
    val title: String = "",
    val choices: List<ModelChoice> = emptyList(),
    val pick: ModelPick = ModelPick.None,
    val newManufacturer: String = "",
    val newModel: String = "",
    val working: Boolean = false,
    val notice: ImportNotice? = null,
    val finished: ImportFinished? = null,
) {
    val canImport: Boolean
        get() = !working && title.isNotBlank() && when (pick) {
            ModelPick.None -> false
            is ModelPick.Existing -> true
            ModelPick.New -> newManufacturer.isNotBlank() && newModel.isNotBlank()
        }
}

/**
 * Runs the import of one PDF: the title and model step first, then [ManualImporter.import], always
 * on [io] and never on the main thread. This is the only caller of the importer.
 *
 * A new model (and its manufacturer) is saved only once the PDF has been stored, so a duplicate
 * leaves no empty model behind. The import itself, and that save, run as one step that a Back or a
 * cleared view model does not interrupt: a manual must never be stored under a model that is not.
 *
 * [scope] ends when the view model is cleared. It defaults to the main thread.
 */
class ImportViewModel(
    private val importer: ManualImporter,
    private val manufacturers: ManufacturerDao,
    private val models: ElevatorModelDao,
    private val manuals: ManualDao,
    private val pdfs: PdfSource,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(scope) {

    private val mutableState = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = mutableState.asStateFlow()

    private var source: Uri? = null

    /** Starts an import of [uri]: shows the title and model step. Ignored while an import is running. */
    fun start(uri: Uri) {
        if (state.value.working) return
        source = uri
        mutableState.value = ImportUiState()
        scope.launch {
            val (choices, name) = try {
                withContext(io) { choices() to pdfs.displayName(uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList<ModelChoice>() to null
            }
            mutableState.update {
                it.copy(
                    choices = choices,
                    // Nothing in the library yet: the only way forward is a new model.
                    pick = if (choices.isEmpty() && it.pick == ModelPick.None) ModelPick.New else it.pick,
                    title = if (it.title.isEmpty()) titleFrom(name) else it.title,
                )
            }
        }
    }

    fun setTitle(title: String) = mutableState.update { it.copy(title = title) }

    fun pickExisting(modelId: String) = mutableState.update { it.copy(pick = ModelPick.Existing(modelId)) }

    fun pickNew() = mutableState.update { it.copy(pick = ModelPick.New) }

    fun setNewManufacturer(name: String) = mutableState.update { it.copy(newManufacturer = name) }

    fun setNewModel(name: String) = mutableState.update { it.copy(newModel = name) }

    /** Imports the PDF with the title and model in the form. Does nothing unless [ImportUiState.canImport]. */
    fun import() {
        val form = state.value
        val uri = source
        if (uri == null || !form.canImport) return
        mutableState.update { it.copy(working = true) }
        scope.launch {
            val outcome = withContext(NonCancellable + io) { run(uri, form) }
            mutableState.update { it.copy(working = false, notice = outcome.notice, finished = outcome.finished) }
        }
    }

    /** Forgets the import: after its notice was dismissed, or its manual was handed to the review. */
    fun reset() {
        if (state.value.working) return
        source = null
        mutableState.value = ImportUiState()
    }

    private class Outcome(val notice: ImportNotice? = null, val finished: ImportFinished? = null)

    private suspend fun run(uri: Uri, form: ImportUiState): Outcome {
        val plan = try {
            planModel(form)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome(ImportNotice.Failed)
        }

        val input = try {
            pdfs.open(uri)
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            // The permission to read a shared PDF can end with the app that shared it.
            null
        } ?: return Outcome(ImportNotice.Unreadable(modelId = null))

        val result = try {
            input.use { importer.import(it, plan.modelId, form.title.trim()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome(ImportNotice.Failed)
        }

        if (result is ImportResult.Duplicate) {
            val existingModel = try {
                manuals.findById(result.existingManualId)?.modelId
            } catch (e: Exception) {
                null
            }
            return Outcome(result.toNotice(existingModel))
        }

        // The manual is stored, so the model it points at has to exist.
        try {
            plan.manufacturer?.let { manufacturers.upsertAll(listOf(it)) }
            plan.model?.let { models.upsertAll(listOf(it)) }
        } catch (e: Exception) {
            return Outcome(ImportNotice.Failed)
        }
        return when (result) {
            is ImportResult.Imported -> Outcome(finished = ImportFinished(plan.modelId, result.manualId))
            else -> Outcome(result.toNotice(plan.modelId))
        }
    }

    /** The model the manual goes to, and the rows to save if that model is new. */
    private class ModelPlan(val modelId: String, val manufacturer: ManufacturerEntity?, val model: ElevatorModelEntity?)

    private suspend fun planModel(form: ImportUiState): ModelPlan {
        val pick = form.pick
        if (pick is ModelPick.Existing) return ModelPlan(pick.modelId, null, null)

        val maker = form.newManufacturer.trim()
        val name = form.newModel.trim()
        val now = clock.millis()
        // A name that is already in the library is reused, not added a second time.
        val knownMaker = manufacturers.getAll().firstOrNull { it.name.equals(maker, ignoreCase = true) }
        val makerId = knownMaker?.id ?: newId()
        val knownModel = models.getAll()
            .firstOrNull { it.manufacturerId == makerId && it.name.equals(name, ignoreCase = true) }
        val modelId = knownModel?.id ?: newId()
        return ModelPlan(
            modelId = modelId,
            manufacturer = if (knownMaker == null) ManufacturerEntity(makerId, maker, now) else null,
            model = if (knownModel == null) {
                ElevatorModelEntity(id = modelId, manufacturerId = makerId, name = name, notes = "", updatedAt = now)
            } else {
                null
            },
        )
    }

    private suspend fun choices(): List<ModelChoice> {
        val makers = manufacturers.getAll().associate { it.id to it.name }
        return models.getAll()
            .map { ModelChoice(it.id, "${makers[it.manufacturerId] ?: UNKNOWN_MANUFACTURER} ${it.name}") }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun titleFrom(displayName: String?): String =
        displayName.orEmpty().trim().removeSuffix(".pdf").removeSuffix(".PDF").trim()

    companion object {
        fun factory(importer: ManualImporter, db: ElevManDatabase, pdfs: PdfSource) = viewModelFactory {
            initializer {
                ImportViewModel(importer, db.manufacturerDao(), db.elevatorModelDao(), db.manualDao(), pdfs)
            }
        }
    }
}
