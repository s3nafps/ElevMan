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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A model whose manufacturer row is missing is still listed, under this name.
internal const val UNKNOWN_MANUFACTURER = "Unknown manufacturer"

/** The screens of the Library tab. Review and Import are full-screen: they hide the tab bar. */
sealed interface LibraryPage {
    data object Overview : LibraryPage
    data class Model(val modelId: String) : LibraryPage
    data class Review(val manualId: String) : LibraryPage
    data object Import : LibraryPage
}

/** A model in the list. [toReview] is the number of suggestions waiting, over all of its manuals. */
data class ModelSummary(val id: String, val name: String, val toReview: Int)

data class ManufacturerGroup(val name: String, val models: List<ModelSummary>)

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
 * What the Library tab shows. [pages] is the back stack, from the overview up to the screen on
 * top. [detail] is the model the top-most Model page is about, once [detailLoaded].
 */
data class LibraryUiState(
    val pages: List<LibraryPage> = listOf(LibraryPage.Overview),
    val loaded: Boolean = false,
    val failed: Boolean = false,
    val manufacturers: List<ManufacturerGroup> = emptyList(),
    val detail: ModelDetail? = null,
    val detailLoaded: Boolean = false,
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
        mutableState.update { if (it.pages.size > 1) it.copy(pages = it.pages.dropLast(1)) else it }
        reload()
    }

    /** After an import: the model's page, with the review of the new manual on top of it. */
    fun showImported(modelId: String, manualId: String) {
        mutableState.update {
            it.copy(
                pages = listOf(LibraryPage.Overview, LibraryPage.Model(modelId), LibraryPage.Review(manualId)),
                detail = null,
                detailLoaded = false,
            )
        }
        reload()
    }

    /** Opens a model's page straight from the overview (for "Open in library"). */
    fun showModel(modelId: String) {
        mutableState.update {
            it.copy(pages = listOf(LibraryPage.Overview, LibraryPage.Model(modelId)), detail = null, detailLoaded = false)
        }
        reload()
    }

    private fun push(page: LibraryPage, resetDetail: Boolean = false) {
        mutableState.update {
            val pages = it.pages + page
            if (resetDetail) it.copy(pages = pages, detail = null, detailLoaded = false) else it.copy(pages = pages)
        }
        reload()
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
            .map { maker -> ManufacturerGroup(maker.name, summaries(models.filter { it.manufacturerId == maker.id })) }
            .toMutableList()
        val orphans = models.filter { it.manufacturerId !in makerIds }
        if (orphans.isNotEmpty()) groups += ManufacturerGroup(UNKNOWN_MANUFACTURER, summaries(orphans))

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
