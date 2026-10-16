package com.elevman.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.elevman.data.ElevManDatabase
import com.elevman.data.ElevatorModelEntity
import com.elevman.data.ErrorCodeEntity
import com.elevman.data.MaintenanceTaskEntity
import com.elevman.data.ReviewRepository
import com.elevman.data.ReviewStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A model whose manufacturer row is missing is still listed, under this name.
internal const val UNKNOWN_MANUFACTURER = "Unknown manufacturer"

internal const val ENTRY_FAILED_MESSAGE = "That entry could not be saved."

/** The screens of the Library tab. Review and Import are full-screen: they hide the tab bar. */
sealed interface LibraryPage {
    data object Overview : LibraryPage
    data class Model(val modelId: String) : LibraryPage
    data class Review(val manualId: String) : LibraryPage
    data object Import : LibraryPage
}

/** A model in the list. [toReview] is the number of suggestions waiting, over all of its manuals. */
data class ModelSummary(val id: String, val name: String, val toReview: Int)

/**
 * A manufacturer and its models. [id] is the manufacturer's id, which is what tells two manufacturers
 * of the same name apart; it is null for the group of models whose manufacturer row is missing.
 */
data class ManufacturerGroup(val id: String?, val name: String, val models: List<ModelSummary>)

/** A manual of a model. [toReview] is the number of its suggestions still waiting. */
data class ManualSummary(val id: String, val title: String, val pageCount: Int, val toReview: Int)

/** Everything the model screen shows. Only confirmed codes and tasks are listed. */
data class ModelDetail(
    val id: String,
    val name: String,
    val manufacturerName: String,
    val manuals: List<ManualSummary>,
    val codes: List<ErrorCodeEntity>,
    val tasks: List<MaintenanceTaskEntity>,
)

/**
 * The form open on the model page, for one manual: the codes and tasks a person types in by hand
 * (the way in when a scanned PDF has no text to read). [manualTitle] is shown in the form so the
 * person can see which manual the entry goes to.
 */
sealed interface ManualEntry {
    val manualId: String
    val manualTitle: String

    /** The form for an error code. */
    data class Code(override val manualId: String, override val manualTitle: String) : ManualEntry

    /** The form for a maintenance task. */
    data class Task(override val manualId: String, override val manualTitle: String) : ManualEntry
}

/**
 * What the error code form hands over, already checked: [code] and [meaning] are not blank and
 * trimmed, and [pageNumber] is 0 when the person left the page empty (0 means no page).
 */
data class NewCode(val code: String, val meaning: String, val causeAndFix: String, val pageNumber: Int)

/** What the task form hands over, already checked: [intervalMonths] is at least 1. [pageNumber] 0 means no page. */
data class NewTask(val name: String, val intervalMonths: Int, val pageNumber: Int)

/**
 * What the Library tab shows. [pages] is the back stack, from the overview up to the screen on
 * top. [detail] is the model the top-most Model page is about, once [detailLoaded].
 *
 * [entry] is the entry form open on the model page, if any. While it is saving, [entrySaving] is
 * true; when the save failed, [entryError] says so and the form stays open.
 */
data class LibraryUiState(
    val pages: List<LibraryPage> = listOf(LibraryPage.Overview),
    val loaded: Boolean = false,
    val failed: Boolean = false,
    val manufacturers: List<ManufacturerGroup> = emptyList(),
    val detail: ModelDetail? = null,
    val detailLoaded: Boolean = false,
    val entry: ManualEntry? = null,
    val entryError: String? = null,
    val entrySaving: Boolean = false,
) {
    val page: LibraryPage get() = pages.last()

    /** True on the full-screen pages, where the tab bar gives way to the screen's own Back. */
    val hidesTabBar: Boolean get() = page is LibraryPage.Review || page is LibraryPage.Import
}

/**
 * Holds the Library tab: which page is open, and the data it lists. Every read of the database
 * runs on [io]; a newer read cancels one still running, so what is shown is the latest.
 *
 * The number "N to review" of a model is [ReviewRepository.suggestedCount] summed over its manuals.
 *
 * The entry forms: [openCodeEntry] and [openTaskEntry] open one for a manual of the model page;
 * [saveCode] and [saveTask] write it through [review] on [io], one save at a time. A save that
 * works closes the form and reads the library again, so the new item is listed; one that fails
 * leaves the form open with [ENTRY_FAILED_MESSAGE], to be tried again or cancelled.
 *
 * [scope] ends when the view model is cleared. It defaults to the main thread.
 */
class LibraryViewModel(
    private val db: ElevManDatabase,
    private val review: ReviewRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(scope) {

    private val mutableState = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null

    init {
        reload()
    }

    fun openModel(modelId: String) = push(LibraryPage.Model(modelId), resetDetail = true)

    fun openReview(manualId: String) = push(LibraryPage.Review(manualId))

    fun openImport() {
        if (state.value.page != LibraryPage.Import) push(LibraryPage.Import)
    }

    /** Goes one page back. Does nothing on the overview. */
    fun back() {
        mutableState.update { if (it.pages.size > 1) it.copy(pages = it.pages.dropLast(1)).withoutEntry() else it }
        reload()
    }

    /** After an import: the model's page, with the review of the new manual on top of it. */
    fun showImported(modelId: String, manualId: String) {
        mutableState.update {
            it.copy(
                pages = listOf(LibraryPage.Overview, LibraryPage.Model(modelId), LibraryPage.Review(manualId)),
                detail = null,
                detailLoaded = false,
            ).withoutEntry()
        }
        reload()
    }

    /** Opens a model's page straight from the overview (for "Open in library"). */
    fun showModel(modelId: String) {
        mutableState.update {
            it.copy(pages = listOf(LibraryPage.Overview, LibraryPage.Model(modelId)), detail = null, detailLoaded = false)
                .withoutEntry()
        }
        reload()
    }

    private fun push(page: LibraryPage, resetDetail: Boolean = false) {
        mutableState.update {
            val pages = it.pages + page
            val next = if (resetDetail) it.copy(pages = pages, detail = null, detailLoaded = false) else it.copy(pages = pages)
            next.withoutEntry()
        }
        reload()
    }

    // An entry form belongs to the model page it was opened on; leaving the page leaves it.
    private fun LibraryUiState.withoutEntry() = copy(entry = null, entryError = null)

    /** Opens the error code form for [manualId], a manual of the model page. Does nothing for any other id. */
    fun openCodeEntry(manualId: String) = openEntry(manualId) { id, title -> ManualEntry.Code(id, title) }

    /** Opens the maintenance task form for [manualId], a manual of the model page. Does nothing for any other id. */
    fun openTaskEntry(manualId: String) = openEntry(manualId) { id, title -> ManualEntry.Task(id, title) }

    private fun openEntry(manualId: String, form: (id: String, title: String) -> ManualEntry) {
        val manual = state.value.detail?.manuals?.firstOrNull { it.id == manualId } ?: return
        mutableState.update { it.copy(entry = form(manual.id, manual.title), entryError = null) }
    }

    /** Closes the entry form without saving, and with it the message of a failed save. */
    fun dismissEntry() {
        mutableState.update { it.withoutEntry() }
    }

    /** Saves [values] as a confirmed error code of the manual the open form is for. Does nothing without an error code form. */
    fun saveCode(values: NewCode) {
        val entry = state.value.entry as? ManualEntry.Code ?: return
        save(entry) {
            review.addManualErrorCode(entry.manualId, values.code, values.meaning, values.causeAndFix, values.pageNumber)
        }
    }

    /** Saves [values] as a confirmed maintenance task of the manual the open form is for. Does nothing without a task form. */
    fun saveTask(values: NewTask) {
        val entry = state.value.entry as? ManualEntry.Task ?: return
        save(entry) {
            review.addManualMaintenanceTask(entry.manualId, values.name, values.intervalMonths, values.pageNumber)
        }
    }

    /**
     * Runs [write] on [io]. A second save while one is running is ignored, so a double tap cannot
     * write the entry twice. On success the form closes and the library is read again; on failure
     * the form stays, with its message, as long as it is still the one that was saving.
     */
    private fun save(entry: ManualEntry, write: suspend () -> Unit) {
        val before = mutableState.getAndUpdate { if (it.entrySaving) it else it.copy(entrySaving = true, entryError = null) }
        if (before.entrySaving) return
        scope.launch {
            try {
                withContext(io) { write() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(entrySaving = false, entryError = if (it.entry == entry) ENTRY_FAILED_MESSAGE else null)
                }
                return@launch
            }
            mutableState.update {
                it.copy(entrySaving = false, entry = if (it.entry == entry) null else it.entry, entryError = null)
            }
            reload()
        }
    }

    /** Reads the library again: the overview, and the model of the top-most Model page. */
    fun reload() {
        loadJob?.cancel()
        val modelId = state.value.pages.filterIsInstance<LibraryPage.Model>().lastOrNull()?.modelId
        loadJob = scope.launch {
            val read = try {
                withContext(io) { read(modelId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(loaded = true, failed = true, detailLoaded = true) }
                return@launch
            }
            mutableState.update {
                it.copy(
                    loaded = true,
                    failed = false,
                    manufacturers = read.manufacturers,
                    detail = read.detail,
                    detailLoaded = true,
                )
            }
        }
    }

    private class Read(val manufacturers: List<ManufacturerGroup>, val detail: ModelDetail?)

    private suspend fun read(modelId: String?): Read {
        val makers = db.manufacturerDao().getAll()
        val models = db.elevatorModelDao().getAll()
        val manuals = db.manualDao().getAll()

        val waiting: Map<String, Int> = manuals.associate { it.id to review.suggestedCount(it.id) }
        val waitingByModel: Map<String, Int> = manuals.groupBy { it.modelId }
            .mapValues { (_, ofModel) -> ofModel.sumOf { waiting.getValue(it.id) } }

        val byName: Comparator<String> = String.CASE_INSENSITIVE_ORDER
        fun summaries(ofMaker: List<ElevatorModelEntity>) =
            ofMaker.sortedWith(compareBy(byName) { it.name })
                .map { ModelSummary(it.id, it.name, waitingByModel[it.id] ?: 0) }

        val makerIds = makers.map { it.id }.toSet()
        val groups = makers.sortedWith(compareBy(byName) { it.name })
            .map { maker -> ManufacturerGroup(maker.id, maker.name, summaries(models.filter { it.manufacturerId == maker.id })) }
            .toMutableList()
        val orphans = models.filter { it.manufacturerId !in makerIds }
        if (orphans.isNotEmpty()) groups += ManufacturerGroup(null, UNKNOWN_MANUFACTURER, summaries(orphans))

        val detail = modelId?.let { id ->
            models.firstOrNull { it.id == id }?.let { model ->
                ModelDetail(
                    id = model.id,
                    name = model.name,
                    manufacturerName = makers.firstOrNull { it.id == model.manufacturerId }?.name ?: UNKNOWN_MANUFACTURER,
                    manuals = manuals.filter { it.modelId == model.id }
                        .sortedWith(compareBy(byName) { it.title })
                        .map { ManualSummary(it.id, it.title, it.pageCount, waiting.getValue(it.id)) },
                    codes = db.errorCodeDao().findByModelAndStatus(model.id, ReviewStatus.CONFIRMED)
                        .sortedWith(compareBy(byName) { it.code }),
                    tasks = db.maintenanceTaskDao().findByModelAndStatus(model.id, ReviewStatus.CONFIRMED)
                        .sortedWith(compareBy(byName) { it.name }),
                )
            }
        }
        return Read(groups, detail)
    }

    companion object {
        fun factory(db: ElevManDatabase, review: ReviewRepository) = viewModelFactory {
            initializer { LibraryViewModel(db, review) }
        }
    }
}
