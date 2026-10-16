package com.elevman.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.elevman.data.ErrorCodeDao
import com.elevman.data.ItemKind
import com.elevman.data.MaintenanceTaskDao
import com.elevman.data.ReviewItem
import com.elevman.data.ReviewRepository
import com.elevman.data.ReviewStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val LOAD_FAILED_MESSAGE = "Could not load the suggestions."
internal const val CHANGE_FAILED_MESSAGE = "That change could not be saved."

/**
 * What the review screen shows: the suggestions still waiting, in the order the repository gave
 * them, and the item being edited, if any. [loaded] is false until the first read has finished.
 */
data class ReviewUiState(
    val items: List<ReviewItem> = emptyList(),
    val loaded: Boolean = false,
    val editing: EditTarget? = null,
    val message: String? = null,
)

/**
 * Holds the review of manual [manualId]. Every read and write of the database runs on [io]; the
 * changes run one after the other, and after each one the list is read again from [review], so
 * what is shown is always what the repository would give, in its order.
 *
 * [scope] ends when the view model is cleared. It defaults to the main thread.
 */
class ReviewViewModel(
    private val manualId: String,
    private val review: ReviewRepository,
    private val codes: ErrorCodeDao,
    private val tasks: MaintenanceTaskDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(scope) {

    private val mutableState = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = mutableState.asStateFlow()

    private val oneAtATime = Mutex()

    init {
        refresh()
    }

    fun accept(item: ReviewItem) = change { review.setStatus(item.kind, item.id, ReviewStatus.CONFIRMED) }

    fun reject(item: ReviewItem) = change { review.setStatus(item.kind, item.id, ReviewStatus.REJECTED) }

    /** Call only after the person has confirmed: it confirms every suggestion of the manual. */
    fun acceptAll() = change { review.acceptAll(manualId) }

    /** Opens the edit dialog with the stored values of [item]. */
    fun startEdit(item: ReviewItem) {
        scope.launch {
            val target = try {
                withContext(io) { loadTarget(item) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(message = LOAD_FAILED_MESSAGE) }
                return@launch
            }
            if (target == null) {
                // Gone since the list was read (for example, removed by a backup import).
                refresh()
            } else {
                mutableState.update { it.copy(editing = target, message = null) }
            }
        }
    }

    /** Closes the dialog without saving. A message of a failed save was for the dialog, so it goes too. */
    fun dismissEdit() {
        mutableState.update { it.copy(editing = null, message = null) }
    }

    /** Saves the corrected fields of [target], then closes the dialog. The item keeps its status. */
    fun save(target: EditTarget) = change(after = { it.copy(editing = null) }) {
        when (target) {
            is EditTarget.Code -> review.editErrorCode(target.id, target.code, target.meaning, target.causeAndFix)
            is EditTarget.Task -> review.editMaintenanceTask(target.id, target.name, target.intervalMonths)
        }
    }

    private suspend fun loadTarget(item: ReviewItem): EditTarget? = when (item.kind) {
        ItemKind.ERROR_CODE -> codes.findById(item.id)?.let {
            EditTarget.Code(it.id, it.code, it.meaning, it.causeAndFix)
        }
        ItemKind.MAINTENANCE_TASK -> tasks.findById(item.id)?.let {
            EditTarget.Task(it.id, it.name, it.intervalMonths)
        }
    }

    /** Reads the list again. The view model outlives the screen, so the screen asks when it is opened. */
    fun refresh() = change(failure = LOAD_FAILED_MESSAGE) {}

    /**
     * Runs [block] on [io], then reads the list again, all under one lock so two quick taps cannot
     * read in the wrong order. On a failure the list stays as it was and [failure] is shown.
     */
    private fun change(
        failure: String = CHANGE_FAILED_MESSAGE,
        after: (ReviewUiState) -> ReviewUiState = { it },
        block: suspend () -> Unit,
    ) {
        scope.launch {
            oneAtATime.withLock {
                val items = try {
                    withContext(io) {
                        block()
                        review.suggestedFor(manualId)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    mutableState.update { it.copy(loaded = true, message = failure) }
                    return@launch
                }
                mutableState.update { after(it.copy(items = items, loaded = true, message = null)) }
            }
        }
    }

    companion object {
        fun factory(
            manualId: String,
            review: ReviewRepository,
            codes: ErrorCodeDao,
            tasks: MaintenanceTaskDao,
        ) = viewModelFactory {
            initializer { ReviewViewModel(manualId, review, codes, tasks) }
        }
    }
}
