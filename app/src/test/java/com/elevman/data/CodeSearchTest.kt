package com.elevman.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.domain.normalizeCode
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodeSearchTest {

    private lateinit var db: ElevManDatabase
    private lateinit var search: CodeSearch

    private val seededAt = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        search = CodeSearch(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedModels(vararg names: Pair<String, String>) {
        db.elevatorModelDao().upsertAll(
            names.map { (id, name) ->
                ElevatorModelEntity(
                    id = id,
                    manufacturerId = "maker-1",
                    name = name,
                    notes = "",
                    updatedAt = seededAt,
                )
            },
        )
    }

    private fun code(
        id: String,
        modelId: String,
        code: String = "E-21",
        meaning: String = "Door fault",
        manualId: String = "manual-$modelId",
        page: Int = 1,
        status: ReviewStatus = ReviewStatus.CONFIRMED,
    ) = ErrorCodeEntity(
        id = id,
        modelId = modelId,
        manualId = manualId,
        code = code,
        normalizedCode = normalizeCode(code),
        meaning = meaning,
        causeAndFix = "",
        pageNumber = page,
        status = status,
        updatedAt = seededAt,
    )

    private suspend fun seedCodes(vararg rows: ErrorCodeEntity) {
        db.errorCodeDao().upsertAll(rows.toList())
    }

    @Test
    fun findsAcrossModelsWithSpellingVariants() = runTest {
        seedModels("m-a" to "Alpha 100", "m-b" to "Beta 200")
        seedCodes(
            code("c1", "m-a", code = "E-21", meaning = "Door zone sensor fault", page = 12),
            code("c2", "m-b", code = "E 21", meaning = "Door lock open", page = 40),
            code("c3", "m-b", code = "E22", meaning = "Overspeed"),
        )

        val hits = search.search("e 21")

        assertEquals(
            listOf(
                CodeHit("E-21", "Door zone sensor fault", "Alpha 100", "manual-m-a", 12, ReviewStatus.CONFIRMED),
                CodeHit("E 21", "Door lock open", "Beta 200", "manual-m-b", 40, ReviewStatus.CONFIRMED),
            ),
            hits,
        )
    }

    @Test
    fun confirmedRankFirst() = runTest {
        seedModels("m-a" to "Alpha 100", "m-b" to "Beta 200")
        seedCodes(
            code("c1", "m-a", status = ReviewStatus.SUGGESTED),
            code("c2", "m-b", status = ReviewStatus.CONFIRMED),
        )

        val hits = search.search("E21")

        assertEquals(
            listOf(ReviewStatus.CONFIRMED to "Beta 200", ReviewStatus.SUGGESTED to "Alpha 100"),
            hits.map { it.status to it.modelName },
        )
    }

    @Test
    fun rejectedNotReturned() = runTest {
        seedModels("m-a" to "Alpha 100")
        seedCodes(
            code("c1", "m-a", status = ReviewStatus.REJECTED),
            code("c2", "m-a", meaning = "Kept", status = ReviewStatus.SUGGESTED),
        )

        val hits = search.search("E21")

        assertEquals(listOf("Kept"), hits.map { it.meaning })
    }

    @Test
    fun prefixDoesNotMatch() = runTest {
        seedModels("m-a" to "Alpha 100")
        seedCodes(
            code("c1", "m-a", code = "E21"),
            code("c2", "m-a", code = "E2"),
        )

        assertEquals(listOf("E2"), search.search("E2").map { it.code })
        assertEquals(listOf("E21"), search.search("e-21").map { it.code })
    }

    @Test
    fun emptyQueryReturnsNothing() = runTest {
        seedModels("m-a" to "Alpha 100")
        seedCodes(code("c1", "m-a"))

        assertTrue(search.search("").isEmpty())
        assertTrue(search.search("   ").isEmpty())
        assertTrue(search.search("- /").isEmpty())
    }

    @Test
    fun sameStatusAndModelOrderedByCodeThenPage() = runTest {
        seedModels("m-a" to "Alpha 100")
        // All three normalize to E21, so they match one query and tie on status and model name.
        seedCodes(
            code("c1", "m-a", code = "E-21", page = 30),
            code("c2", "m-a", code = "E 21", page = 50),
            code("c3", "m-a", code = "E-21", page = 5),
            code("c4", "m-a", code = "E21", page = 1),
        )

        val hits = search.search("E21")

        // "E 21" < "E-21" < "E21" in code-point order.
        assertEquals(
            listOf("E 21" to 50, "E-21" to 5, "E-21" to 30, "E21" to 1),
            hits.map { it.code to it.pageNumber },
        )
    }
}
