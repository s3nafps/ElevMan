package com.elevman.ui.elevators

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.withTransaction
import com.elevman.data.DueItem
import com.elevman.data.ElevManDatabase
import com.elevman.data.ElevatorEntity
import com.elevman.data.ElevatorRepository
import com.elevman.data.ReviewStatus
import com.elevman.domain.canMarkDone
import com.elevman.ui.library.ModelChoice
import com.elevman.ui.library.UNKNOWN_MANUFACTURER
import java.time.Clock
import java.time.LocalDate
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

// The site of an elevator that was saved without one is still listed, under this name.
internal const val NO_SITE = "No site"
internal const val FUTURE_DATE_MESSAGE = "Choose today or an earlier date."
internal const val ELEVATOR_SAVE_FAILED_MESSAGE = "Could not save. Try again."
internal const val BUSY_MESSAGE = "Still saving the last change. Try again in a moment."
internal const val TASK_NOT_AVAILABLE_MESSAGE = "That task cannot be assigned to this elevator."

private const val UNKNOWN_MODEL = "Unknown model"
private const val REMOVED_TASK = "Removed task"

/** The pages of the Elevators tab. */
sealed interface ElevatorPage {
    data object Overview : ElevatorPage
    data class Detail(val elevatorId: String) : ElevatorPage
    data object AddElevator : ElevatorPage
}

/** An elevator in the list. [modelName] reads "Manufacturer Model". */
data class ElevatorSummary(val id: String, val modelName: String, val serialNumber: String, val customer: String)

/** The elevators of one site. */
data class SiteGroup(val site: String, val elevators: List<ElevatorSummary>)

/** One line of an elevator's log. [taskName] is null for a plain note. */
data class LogLine(val id: String, val date: LocalDate, val taskName: String?, val note: String)

/** A maintenance task that can still be assigned to the elevator. */
data class TaskChoice(val id: String, val name: String, val intervalMonths: Int)

/** Everything the elevator page shows. [tasks] come overdue first, [log] newest first. */
data class ElevatorDetail(
    val id: String,
    val site: String,
    val customer: String,
    val modelName: String,
    val serialNumber: String,
    val notes: String,
    val tasks: List<DueItem>,
    val log: List<LogLine>,
    val assignable: List<TaskChoice>,
)

/**
 * What the Elevators tab shows. [pages] is the back stack, from the list up to the page on top.
 * [detail] is the elevator of the top-most Detail page, once [detailLoaded]. [busy] is true while a
 * change is being saved; [message] says why a change was not.
 */
data class ElevatorUiState(
    val pages: List<ElevatorPage> = listOf(ElevatorPage.Overview),
    val loaded: Boolean = false,
    val failed: Boolean = false,
    val groups: List<SiteGroup> = emptyList(),
    val models: List<ModelChoice> = emptyList(),
    val detail: ElevatorDetail? = null,
    val detailLoaded: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val page: ElevatorPage get() = pages.last()
}

/**
 * Holds the Elevators tab: which page is open, and the elevators, models and tasks it lists. Every
 * read and write of the database runs on [io], never on the main thread; a newer read cancels one
 * still running, so what is shown is the latest.
 *
 * Marking a task done and adding a note both refuse a date after today ([canMarkDone]) before the
 * repository is called, so the repository never sees one. Assigning a task is refused unless the task
 * is in the elevator's current assignable list. [clock] says what today is, and should be the clock
 * [repository] uses.
 *
 * Every change returns whether it was accepted. A refused one (a future date, a task that cannot be
 * assigned, another change still being saved) sets [ElevatorUiState.message] and returns false, so
 * the screen keeps its dialog open instead of dropping the tap.
 *
 * [scope] ends when the view model is cleared. It defaults to the main thread.
 */
class ElevatorViewModel(
    private val db: ElevManDatabase,
    private val repository: ElevatorRepository,
    private val clock: Clock,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(scope) {

    private val mutableState = MutableStateFlow(ElevatorUiState())
    val state: StateFlow<ElevatorUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null

    init {
        reload()
    }

    /** Today on this phone. Asked for when a date picker opens, so a tab left open overnight is right. */
    fun today(): LocalDate = LocalDate.now(clock)

    fun openElevator(elevatorId: String) = push(ElevatorPage.Detail(elevatorId), resetDetail = true)

    fun openAddElevator() = push(ElevatorPage.AddElevator)

    /** Goes one page back. Does nothing on the list. */
    fun back() {
        mutableState.update { if (it.pages.size > 1) it.copy(pages = it.pages.dropLast(1)) else it }
        reload()
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    private fun push(page: ElevatorPage, resetDetail: Boolean = false) {
        mutableState.update {
            val pages = it.pages + page
            if (resetDetail) it.copy(pages = pages, detail = null, detailLoaded = false) else it.copy(pages = pages)
        }
        reload()
    }

    /** Reads the elevators again: the list, the models, and the elevator of the top-most Detail page. */
    fun reload() {
        loadJob?.cancel()
        val detailId = state.value.pages.filterIsInstance<ElevatorPage.Detail>().lastOrNull()?.elevatorId
        loadJob = scope.launch {
            val read = try {
                withContext(io) { read(detailId) }
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
                    groups = read.groups,
                    models = read.models,
                    detail = read.detail,
                    detailLoaded = true,
                )
            }
        }
    }

    // ---- Changes -------------------------------------------------------------------------------

    /** Saves a new elevator, then returns to the list. Site and model are required by the form, not here. */
    fun addElevator(site: String, customer: String, modelId: String, serialNumber: String, notes: String): Boolean =
        change(
            write = {
                repository.addElevator(site.trim(), customer.trim(), modelId, serialNumber.trim(), notes.trim())
            },
            after = { it.copy(pages = it.pages.filterNot { page -> page == ElevatorPage.AddElevator }) },
        )

    /** Records that the elevator task [elevatorTaskId] was done on [date]. A date after today is refused. */
    fun markDone(elevatorTaskId: String, date: LocalDate): Boolean {
        if (refuseFuture(date)) return false
        return change(write = { repository.markDone(elevatorTaskId, date, "") })
    }

    /** Adds a note to the log of the open elevator. A date after today is refused. */
    fun addNote(date: LocalDate, note: String): Boolean {
        val elevatorId = openElevatorId() ?: return false
        if (refuseFuture(date)) return false
        return change(write = { repository.addNote(elevatorId, date, note.trim()) })
    }

    /**
     * Assigns a maintenance task to the open elevator, if it is one of the tasks the elevator can
     * still be given (see [ElevatorDetail.assignable]); any other id is refused with a message. The
     * dialog that lists the choices is not relied on. The repository would also keep a single row for
     * a task the elevator already has.
     */
    fun assignTask(maintenanceTaskId: String): Boolean {
        val elevatorId = openElevatorId() ?: return false
        val detail = state.value.detail
        if (detail == null || detail.id != elevatorId || detail.assignable.none { it.id == maintenanceTaskId }) {
            mutableState.update { it.copy(message = TASK_NOT_AVAILABLE_MESSAGE) }
            return false
        }
        return change(write = { repository.assignTask(elevatorId, maintenanceTaskId) })
    }

    private fun openElevatorId(): String? =
        (state.value.page as? ElevatorPage.Detail)?.elevatorId

    /** True, with a message, when [date] is after today. */
    private fun refuseFuture(date: LocalDate): Boolean {
        if (canMarkDone(date, today())) return false
        mutableState.update { it.copy(message = FUTURE_DATE_MESSAGE) }
        return true
    }

    /**
     * Runs [write] on [io], then [after] on the state and a reload, and returns true. One change at a
     * time: while another is being saved this changes nothing, shows [BUSY_MESSAGE] and returns false.
     */
    private fun change(write: suspend () -> Unit, after: (ElevatorUiState) -> ElevatorUiState = { it }): Boolean {
        if (state.value.busy) {
            mutableState.update { it.copy(message = BUSY_MESSAGE) }
            return false
        }
        mutableState.update { it.copy(busy = true) }
        scope.launch {
            val failed = try {
                withContext(io) { write() }
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                true
            }
            mutableState.update {
                val next = if (failed) it.copy(message = ELEVATOR_SAVE_FAILED_MESSAGE) else after(it)
                next.copy(busy = false)
            }
            reload()
        }
        return true
    }

    // ---- Reading -------------------------------------------------------------------------------

    private class Read(val groups: List<SiteGroup>, val models: List<ModelChoice>, val detail: ElevatorDetail?)

    private suspend fun read(detailId: String?): Read = db.withTransaction {
        val makers = db.manufacturerDao().getAll().associate { it.id to it.name }
        val modelNames = db.elevatorModelDao().getAll().associate { model ->
            model.id to "${makers[model.manufacturerId] ?: UNKNOWN_MANUFACTURER} ${model.name}"
        }
        val elevators = db.elevatorDao().getAll()
        val byName = String.CASE_INSENSITIVE_ORDER

        val groups = elevators
            .groupBy { it.site.trim().ifEmpty { NO_SITE } }
            .map { (site, ofSite) ->
                val summaries = ofSite
                    .map { ElevatorSummary(it.id, modelNames[it.modelId] ?: UNKNOWN_MODEL, it.serialNumber, it.customer) }
                    .sortedWith(
                        compareBy<ElevatorSummary, String>(byName) { it.modelName }
                            .thenBy(byName) { it.serialNumber }
                            .thenBy { it.id },
                    )
                SiteGroup(site, summaries)
            }
            // The elevators with no site come last.
            .sortedWith(compareBy<SiteGroup> { it.site == NO_SITE }.thenBy(byName) { it.site })

        val models = modelNames.map { (id, label) -> ModelChoice(id, label) }
            .sortedWith(compareBy(byName) { it.label })

        val detail = detailId?.let { id ->
            elevators.firstOrNull { it.id == id }?.let { detailOf(it, modelNames[it.modelId] ?: UNKNOWN_MODEL) }
        }
        Read(groups, models, detail)
    }

    private suspend fun detailOf(elevator: ElevatorEntity, modelName: String): ElevatorDetail {
        val tasks = db.maintenanceTaskDao()
        val assigned = db.elevatorTaskDao().findByElevatorId(elevator.id).map { it.maintenanceTaskId }.toSet()
        val taskNames = tasks.getAll().associate { it.id to it.name }
        return ElevatorDetail(
            id = elevator.id,
            site = elevator.site.trim().ifEmpty { NO_SITE },
            customer = elevator.customer,
            modelName = modelName,
            serialNumber = elevator.serialNumber,
            notes = elevator.notes,
            tasks = repository.dueItems(elevator.id),
            log = repository.log(elevator.id).map { entry ->
                LogLine(entry.id, entry.date, entry.maintenanceTaskId?.let { taskNames[it] ?: REMOVED_TASK }, entry.note)
            },
            // Only tasks someone has confirmed from the manual, and only the ones it does not have yet.
            assignable = tasks.findByModelAndStatus(elevator.modelId, ReviewStatus.CONFIRMED)
                .filter { it.id !in assigned }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .map { TaskChoice(it.id, it.name, it.intervalMonths) },
        )
    }

    companion object {
        fun factory(db: ElevManDatabase, repository: ElevatorRepository, clock: Clock) = viewModelFactory {
            initializer { ElevatorViewModel(db, repository, clock) }
        }
    }
}
