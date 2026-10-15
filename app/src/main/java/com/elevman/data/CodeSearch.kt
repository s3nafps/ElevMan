package com.elevman.data

import com.elevman.domain.normalizeCode

/**
 * One error code found by [CodeSearch], ready to display. [code] and [meaning] are as printed in
 * the manual, [modelName] is the name of the elevator model the manual belongs to, and [manualId]
 * and [pageNumber] say where to read more.
 */
data class CodeHit(
    val code: String,
    val meaning: String,
    val modelName: String,
    val manualId: String,
    val pageNumber: Int,
    val status: ReviewStatus,
)

/** Finds an error code across every elevator model. */
class CodeSearch(private val db: ElevManDatabase) {

    /**
     * The error codes whose normalized code equals `normalizeCode(query)`, so "e 21" finds "E-21"
     * but "E2" does not find "E21". [ReviewStatus.REJECTED] codes are left out. A query with nothing
     * to match on, such as an empty or blank one, returns an empty list.
     *
     * The list is ordered by status ([ReviewStatus.CONFIRMED] first), then model name, code, page
     * number and manual id, so equal hits always come back in the same order.
     *
     * A code whose model row is missing is kept, with an empty [CodeHit.modelName].
     */
    suspend fun search(query: String): List<CodeHit> {
        val key = normalizeCode(query)
        if (key.isEmpty()) return emptyList()

        val rows = db.errorCodeDao().findByKeyExcludingStatus(key, ReviewStatus.REJECTED)
        if (rows.isEmpty()) return emptyList()

        val modelNames = db.elevatorModelDao().getAll().associate { it.id to it.name }
        return rows
            .map {
                CodeHit(
                    code = it.code,
                    meaning = it.meaning,
                    modelName = modelNames[it.modelId].orEmpty(),
                    manualId = it.manualId,
                    pageNumber = it.pageNumber,
                    status = it.status,
                )
            }
            .sortedWith(
                compareBy<CodeHit>(
                    { statusRank(it.status) },
                    { it.modelName },
                    { it.code },
                    { it.pageNumber },
                    { it.manualId },
                ),
            )
    }

    // Written out rather than taken from the enum's ordinal, which puts SUGGESTED before CONFIRMED.
    private fun statusRank(status: ReviewStatus): Int = when (status) {
        ReviewStatus.CONFIRMED -> 0
        ReviewStatus.SUGGESTED -> 1
        ReviewStatus.REJECTED -> 2
    }
}
