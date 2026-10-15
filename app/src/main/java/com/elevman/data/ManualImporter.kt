package com.elevman.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.elevman.domain.normalizeCode
import com.elevman.pdf.PageTextSource
import com.elevman.pdf.SuggestionParser
import com.elevman.pdf.hasAnyText
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.util.UUID

sealed interface ImportResult {
    /** The manual was stored and its suggestions are waiting for review. */
    data class Imported(val manualId: String, val suggestedCodes: Int, val suggestedTasks: Int) : ImportResult

    /** The manual was stored, but it has no text layer (a scan), so there is nothing to suggest. */
    data class NoText(val manualId: String) : ImportResult

    /** A PDF with identical bytes was imported before; nothing was stored or extracted. */
    data class Duplicate(val existingManualId: String, val existingTitle: String) : ImportResult

    /** The manual was stored, but the PDF could not be parsed. It has no page count and no suggestions. */
    data class Unreadable(val manualId: String, val reason: String) : ImportResult
}

/**
 * Stores an imported PDF, reads its text, and saves what [SuggestionParser] finds as
 * [ReviewStatus.SUGGESTED] rows for a person to review.
 *
 * [db] is the database that owns [codes] and [tasks]; it makes the suggested rows of one import
 * land together or not at all.
 */
class ManualImporter(
    private val store: ManualStore,
    private val text: PageTextSource,
    private val manuals: ManualDao,
    private val codes: ErrorCodeDao,
    private val tasks: MaintenanceTaskDao,
    private val clock: Clock,
    private val db: RoomDatabase,
) {

    /** Imports [source] as a manual of [modelId]. The caller owns [source] and must close it. */
    suspend fun import(source: InputStream, modelId: String, title: String): ImportResult {
        val stored = when (val outcome = store.store(source, modelId, title)) {
            is StoreOutcome.Duplicate ->
                return ImportResult.Duplicate(outcome.existingManualId, outcome.existingTitle)
            is StoreOutcome.Stored -> outcome
        }

        val pages = try {
            text.pages(stored.file)
        } catch (e: IOException) {
            // The row stays so the person can see the manual and open it, with pageCount 0.
            return ImportResult.Unreadable(stored.manualId, e.message ?: e.javaClass.name)
        }

        manuals.findById(stored.manualId)?.let { manuals.upsertAll(listOf(it.copy(pageCount = pages.size))) }

        if (!hasAnyText(pages)) return ImportResult.NoText(stored.manualId)

        val now = clock.millis()
        val codeRows = SuggestionParser.errorCodes(pages).map {
            ErrorCodeEntity(
                id = UUID.randomUUID().toString(),
                modelId = modelId,
                manualId = stored.manualId,
                code = it.code,
                normalizedCode = normalizeCode(it.code),
                meaning = it.meaning,
                causeAndFix = "",
                pageNumber = it.pageNumber,
                status = ReviewStatus.SUGGESTED,
                updatedAt = now,
            )
        }
        val taskRows = SuggestionParser.maintenanceTasks(pages).map {
            MaintenanceTaskEntity(
                id = UUID.randomUUID().toString(),
                modelId = modelId,
                manualId = stored.manualId,
                name = it.name,
                intervalMonths = it.intervalMonths,
                pageNumber = it.pageNumber,
                status = ReviewStatus.SUGGESTED,
                updatedAt = now,
            )
        }

        db.withTransaction {
            codes.upsertAll(codeRows)
            tasks.upsertAll(taskRows)
        }
        return ImportResult.Imported(stored.manualId, codeRows.size, taskRows.size)
    }
}
