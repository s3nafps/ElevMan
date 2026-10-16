package com.elevman.data

import androidx.room.withTransaction
import com.elevman.domain.normalizeCode
import java.time.Clock
import java.util.UUID

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

    /**
     * The items of [manualId] still waiting for review, ordered by page, then by label, then by kind
     * (error codes before maintenance tasks), then by id. The last two make the order the same
     * every time, whatever order the database returns the rows in.
     */
    suspend fun suggestedFor(manualId: String): List<ReviewItem> {
        val codeItems = codes.findByManualAndStatus(manualId, ReviewStatus.SUGGESTED).map {
            ReviewItem(ItemKind.ERROR_CODE, it.id, it.code, it.meaning, it.pageNumber)
        }
        val taskItems = tasks.findByManualAndStatus(manualId, ReviewStatus.SUGGESTED).map {
            ReviewItem(ItemKind.MAINTENANCE_TASK, it.id, it.name, "every ${it.intervalMonths} months", it.pageNumber)
        }
        return (codeItems + taskItems).sortedWith(compareBy({ it.pageNumber }, { it.label }, { it.kind }, { it.id }))
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
        requireValidInterval(intervalMonths)
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

    /**
     * Saves an error code a person typed in for [manualId], already [ReviewStatus.CONFIRMED]: nobody
     * needs to review what they entered themselves. The model is the manual's. [pageNumber] 0 means
     * the code has no page. `normalizedCode` is computed from [code], so a search finds it however
     * it is spelled. The text fields are trimmed.
     *
     * @return the id of the new row.
     * @throws IllegalArgumentException when [code] has no letter or digit in it (a search could never
     *   find it), when [meaning] is blank, when [pageNumber] is negative, or when no manual has this id.
     */
    suspend fun addManualErrorCode(
        manualId: String,
        code: String,
        meaning: String,
        causeAndFix: String = "",
        pageNumber: Int = 0,
    ): String {
        val normalized = normalizeCode(code)
        require(normalized.isNotEmpty()) { "code needs at least one letter or digit" }
        require(meaning.isNotBlank()) { "meaning must not be blank" }
        requireValidPage(pageNumber)
        val manual = requireManual(manualId)
        val row = ErrorCodeEntity(
            id = UUID.randomUUID().toString(),
            modelId = manual.modelId,
            manualId = manual.id,
            code = code.trim(),
            normalizedCode = normalized,
            meaning = meaning.trim(),
            causeAndFix = causeAndFix.trim(),
            pageNumber = pageNumber,
            status = ReviewStatus.CONFIRMED,
            updatedAt = clock.millis(),
        )
        codes.upsertAll(listOf(row))
        return row.id
    }

    /**
     * Saves a maintenance task a person typed in for [manualId], already [ReviewStatus.CONFIRMED].
     * The model is the manual's. [pageNumber] 0 means the task has no page. The name is trimmed.
     *
     * @return the id of the new row.
     * @throws IllegalArgumentException when [intervalMonths] is below 1, when [name] is blank, when
     *   [pageNumber] is negative, or when no manual has this id.
     */
    suspend fun addManualMaintenanceTask(
        manualId: String,
        name: String,
        intervalMonths: Int,
        pageNumber: Int = 0,
    ): String {
        requireValidInterval(intervalMonths)
        require(name.isNotBlank()) { "name must not be blank" }
        requireValidPage(pageNumber)
        val manual = requireManual(manualId)
        val row = MaintenanceTaskEntity(
            id = UUID.randomUUID().toString(),
            modelId = manual.modelId,
            manualId = manual.id,
            name = name.trim(),
            intervalMonths = intervalMonths,
            pageNumber = pageNumber,
            status = ReviewStatus.CONFIRMED,
            updatedAt = clock.millis(),
        )
        tasks.upsertAll(listOf(row))
        return row.id
    }

    private fun requireValidInterval(intervalMonths: Int) =
        require(intervalMonths >= 1) { "intervalMonths must be at least 1, was $intervalMonths" }

    private fun requireValidPage(pageNumber: Int) =
        require(pageNumber >= 0) { "pageNumber must not be negative, was $pageNumber" }

    private suspend fun requireManual(manualId: String): ManualEntity =
        requireNotNull(db.manualDao().findById(manualId)) { "no manual with id $manualId" }
}
