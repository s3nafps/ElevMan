package com.elevman.data

import androidx.room.withTransaction
import com.elevman.domain.normalizeCode
import java.time.Clock

enum class ItemKind { ERROR_CODE, MAINTENANCE_TASK }

/**
 * One row of the review list, ready to display. For an error code, [label] is the code as printed
 * and [detail] its meaning. For a maintenance task, [label] is its name and [detail] its interval.
 */
data class ReviewItem(
    val kind: ItemKind,
    val id: String,
    val label: String,
    val detail: String,
    val pageNumber: Int,
)

/**
 * Lets a person review the [ReviewStatus.SUGGESTED] error codes and maintenance tasks that came from
 * a manual: confirm, reject, or correct them. Every change sets `updatedAt` from [clock].
 */
class ReviewRepository(
    private val db: ElevManDatabase,
    private val clock: Clock,
) {
    private val codes get() = db.errorCodeDao()
    private val tasks get() = db.maintenanceTaskDao()

    /** The items of [manualId] still waiting for review, ordered by page, then by label. */
    suspend fun suggestedFor(manualId: String): List<ReviewItem> {
        val codeItems = codes.findByManualAndStatus(manualId, ReviewStatus.SUGGESTED).map {
            ReviewItem(ItemKind.ERROR_CODE, it.id, it.code, it.meaning, it.pageNumber)
        }
        val taskItems = tasks.findByManualAndStatus(manualId, ReviewStatus.SUGGESTED).map {
            ReviewItem(ItemKind.MAINTENANCE_TASK, it.id, it.name, "every ${it.intervalMonths} months", it.pageNumber)
        }
        return (codeItems + taskItems).sortedWith(compareBy({ it.pageNumber }, { it.label }))
    }

    /** How many error codes and maintenance tasks of [manualId] are still waiting for review. */
    suspend fun suggestedCount(manualId: String): Int =
        codes.findByManualAndStatus(manualId, ReviewStatus.SUGGESTED).size +
            tasks.findByManualAndStatus(manualId, ReviewStatus.SUGGESTED).size

    /** Sets the status of one item. Does nothing when no item of [kind] has this [id]. */
    suspend fun setStatus(kind: ItemKind, id: String, status: ReviewStatus) {
        val now = clock.millis()
        db.withTransaction {
            when (kind) {
                ItemKind.ERROR_CODE -> codes.findById(id)
                    ?.let { codes.upsertAll(listOf(it.copy(status = status, updatedAt = now))) }
                ItemKind.MAINTENANCE_TASK -> tasks.findById(id)
                    ?.let { tasks.upsertAll(listOf(it.copy(status = status, updatedAt = now))) }
            }
        }
    }

    /**
     * Corrects an error code. The status is kept, and `normalizedCode` is recomputed from [code].
     * Does nothing when no error code has this [id].
     */
    suspend fun editErrorCode(id: String, code: String, meaning: String, causeAndFix: String) {
        val now = clock.millis()
        db.withTransaction {
            codes.findById(id)?.let {
                codes.upsertAll(
                    listOf(
                        it.copy(
                            code = code,
                            normalizedCode = normalizeCode(code),
                            meaning = meaning,
                            causeAndFix = causeAndFix,
                            updatedAt = now,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Corrects a maintenance task. The status is kept. Does nothing when no task has this [id].
     *
     * @throws IllegalArgumentException when [intervalMonths] is below 1.
     */
    suspend fun editMaintenanceTask(id: String, name: String, intervalMonths: Int) {
        require(intervalMonths >= 1) { "intervalMonths must be at least 1, was $intervalMonths" }
        val now = clock.millis()
        db.withTransaction {
            tasks.findById(id)?.let {
                tasks.upsertAll(listOf(it.copy(name = name, intervalMonths = intervalMonths, updatedAt = now)))
            }
        }
    }

    /**
     * Confirms every [ReviewStatus.SUGGESTED] error code and maintenance task of [manualId], in one
     * transaction. Rejected and already confirmed items, and other manuals, are not touched.
     *
     * @return how many items were changed.
     */
    suspend fun acceptAll(manualId: String): Int {
        val now = clock.millis()
        return db.withTransaction {
            val suggestedCodes = codes.findByManualAndStatus(manualId, ReviewStatus.SUGGESTED)
            val suggestedTasks = tasks.findByManualAndStatus(manualId, ReviewStatus.SUGGESTED)
            codes.upsertAll(suggestedCodes.map { it.copy(status = ReviewStatus.CONFIRMED, updatedAt = now) })
            tasks.upsertAll(suggestedTasks.map { it.copy(status = ReviewStatus.CONFIRMED, updatedAt = now) })
            suggestedCodes.size + suggestedTasks.size
        }
    }
}
