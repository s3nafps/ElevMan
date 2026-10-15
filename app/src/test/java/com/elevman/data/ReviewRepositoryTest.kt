package com.elevman.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewRepositoryTest {

    private lateinit var db: ElevManDatabase
    private lateinit var repo: ReviewRepository

    private val seededAt = 1_000L
    private val now = 1_700_000_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ReviewRepository(db, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun code(
        id: String,
        manualId: String,
        code: String = "E21",
        meaning: String = "Door fault",
        page: Int = 1,
        status: ReviewStatus = ReviewStatus.SUGGESTED,
    ) = ErrorCodeEntity(
        id = id,
        modelId = "model-1",
        manualId = manualId,
        code = code,
        normalizedCode = code.filter { it.isLetterOrDigit() }.uppercase(),
        meaning = meaning,
        causeAndFix = "",
        pageNumber = page,
        status = status,
        updatedAt = seededAt,
    )

    private fun task(
        id: String,
        manualId: String,
        name: String = "Lubricate guide rails",
        months: Int = 6,
        page: Int = 1,
        status: ReviewStatus = ReviewStatus.SUGGESTED,
    ) = MaintenanceTaskEntity(
        id = id,
        modelId = "model-1",
        manualId = manualId,
        name = name,
        intervalMonths = months,
        pageNumber = page,
        status = status,
        updatedAt = seededAt,
    )

    private suspend fun assertThrowsIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return
        }
        fail("expected IllegalArgumentException")
    }

    private suspend fun seed(codes: List<ErrorCodeEntity> = emptyList(), tasks: List<MaintenanceTaskEntity> = emptyList()) {
        db.errorCodeDao().upsertAll(codes)
        db.maintenanceTaskDao().upsertAll(tasks)
    }

    @Test
    fun acceptAllConfirmsOnlySuggestedOfThisManual() = runTest {
        seed(
            codes = listOf(
                code("a-c1", "A"),
                code("a-c2", "A"),
                code("a-c3", "A", status = ReviewStatus.REJECTED),
                code("a-c4", "A", status = ReviewStatus.CONFIRMED),
                code("b-c1", "B"),
            ),
            tasks = listOf(
                task("a-t1", "A"),
                task("a-t2", "A", status = ReviewStatus.REJECTED),
                task("b-t1", "B"),
                task("b-t2", "B"),
            ),
        )

        val changed = repo.acceptAll("A")

        assertEquals(3, changed)
        val codes = db.errorCodeDao().getAll().associateBy { it.id }
        val tasks = db.maintenanceTaskDao().getAll().associateBy { it.id }
        assertEquals(ReviewStatus.CONFIRMED, codes.getValue("a-c1").status)
        assertEquals(ReviewStatus.CONFIRMED, codes.getValue("a-c2").status)
        assertEquals(ReviewStatus.REJECTED, codes.getValue("a-c3").status)
        assertEquals(ReviewStatus.CONFIRMED, codes.getValue("a-c4").status)
        assertEquals(ReviewStatus.CONFIRMED, tasks.getValue("a-t1").status)
        assertEquals(ReviewStatus.REJECTED, tasks.getValue("a-t2").status)
        // Manual B is untouched, including updatedAt.
        assertEquals(ReviewStatus.SUGGESTED, codes.getValue("b-c1").status)
        assertEquals(ReviewStatus.SUGGESTED, tasks.getValue("b-t1").status)
        assertEquals(ReviewStatus.SUGGESTED, tasks.getValue("b-t2").status)
        assertEquals(seededAt, codes.getValue("b-c1").updatedAt)
        assertEquals(seededAt, tasks.getValue("b-t1").updatedAt)
        // Changed rows get the clock time; rows that were not changed keep theirs.
        assertEquals(now, codes.getValue("a-c1").updatedAt)
        assertEquals(now, tasks.getValue("a-t1").updatedAt)
        assertEquals(seededAt, codes.getValue("a-c3").updatedAt)
        assertEquals(seededAt, codes.getValue("a-c4").updatedAt)
        assertEquals(seededAt, tasks.getValue("a-t2").updatedAt)
        // Nothing left to review in A, B still waiting.
        assertEquals(0, repo.suggestedCount("A"))
        assertEquals(3, repo.suggestedCount("B"))
    }

    @Test
    fun acceptAllReturnsZeroWhenNothingIsSuggested() = runTest {
        seed(codes = listOf(code("c1", "A", status = ReviewStatus.REJECTED)))

        assertEquals(0, repo.acceptAll("A"))
        assertEquals(0, repo.acceptAll("unknown-manual"))
    }

    @Test
    fun editRecomputesNormalizedCode() = runTest {
        seed(codes = listOf(code("c1", "A", code = "E21", status = ReviewStatus.CONFIRMED)))

        repo.editErrorCode("c1", code = "e-22", meaning = "Door lock fault", causeAndFix = "Replace the lock")

        val row = db.errorCodeDao().findById("c1")!!
        assertEquals("e-22", row.code)
        assertEquals("E22", row.normalizedCode)
        assertEquals("Door lock fault", row.meaning)
        assertEquals("Replace the lock", row.causeAndFix)
        assertEquals(ReviewStatus.CONFIRMED, row.status)
        assertEquals(now, row.updatedAt)
        assertEquals("A", row.manualId)
        assertEquals(1, row.pageNumber)
    }

    @Test
    fun editKeepsSuggestedStatus() = runTest {
        seed(codes = listOf(code("c1", "A")))

        repo.editErrorCode("c1", code = "E30", meaning = "New", causeAndFix = "")

        assertEquals(ReviewStatus.SUGGESTED, db.errorCodeDao().findById("c1")!!.status)
    }

    @Test
    fun editMaintenanceTaskChangesNameAndIntervalAndKeepsStatus() = runTest {
        seed(tasks = listOf(task("t1", "A", months = 6, status = ReviewStatus.CONFIRMED)))

        repo.editMaintenanceTask("t1", name = "Inspect door rollers", intervalMonths = 12)

        val row = db.maintenanceTaskDao().findById("t1")!!
        assertEquals("Inspect door rollers", row.name)
        assertEquals(12, row.intervalMonths)
        assertEquals(ReviewStatus.CONFIRMED, row.status)
        assertEquals(now, row.updatedAt)
        assertEquals("A", row.manualId)
        assertEquals(1, row.pageNumber)
    }

    @Test
    fun editMaintenanceTaskRejectsIntervalBelowOne() = runTest {
        seed(tasks = listOf(task("t1", "A", months = 6)))

        assertThrowsIllegalArgument { repo.editMaintenanceTask("t1", name = "x", intervalMonths = 0) }
        assertThrowsIllegalArgument { repo.editMaintenanceTask("t1", name = "x", intervalMonths = -3) }

        val row = db.maintenanceTaskDao().findById("t1")!!
        assertEquals("Lubricate guide rails", row.name)
        assertEquals(6, row.intervalMonths)
        assertEquals(seededAt, row.updatedAt)
    }

    @Test
    fun editMaintenanceTaskAcceptsIntervalOfOne() = runTest {
        seed(tasks = listOf(task("t1", "A", months = 6)))

        repo.editMaintenanceTask("t1", name = "Monthly check", intervalMonths = 1)

        assertEquals(1, db.maintenanceTaskDao().findById("t1")!!.intervalMonths)
    }

    @Test
    fun editsOfAMissingIdDoNothing() = runTest {
        seed(codes = listOf(code("c1", "A")), tasks = listOf(task("t1", "A")))

        repo.editErrorCode("missing", code = "E99", meaning = "m", causeAndFix = "c")
        repo.editMaintenanceTask("missing", name = "n", intervalMonths = 3)

        assertEquals(listOf(code("c1", "A")), db.errorCodeDao().getAll())
        assertEquals(listOf(task("t1", "A")), db.maintenanceTaskDao().getAll())
    }

    @Test
    fun editMaintenanceTaskOfAMissingIdStillRejectsBadInterval() = runTest {
        assertThrowsIllegalArgument { repo.editMaintenanceTask("missing", name = "n", intervalMonths = 0) }
    }

    @Test
    fun rejectHidesItemFromSuggestions() = runTest {
        seed(
            codes = listOf(code("c1", "A", page = 1), code("c2", "A", page = 2)),
            tasks = listOf(task("t1", "A", page = 3)),
        )

        repo.setStatus(ItemKind.ERROR_CODE, "c1", ReviewStatus.REJECTED)
        repo.setStatus(ItemKind.MAINTENANCE_TASK, "t1", ReviewStatus.REJECTED)

        val suggested = repo.suggestedFor("A")
        assertEquals(listOf("c2"), suggested.map { it.id })
        assertEquals(1, repo.suggestedCount("A"))
        assertEquals(ReviewStatus.REJECTED, db.errorCodeDao().findById("c1")!!.status)
        assertEquals(now, db.errorCodeDao().findById("c1")!!.updatedAt)
        assertEquals(ReviewStatus.REJECTED, db.maintenanceTaskDao().findById("t1")!!.status)
        assertEquals(now, db.maintenanceTaskDao().findById("t1")!!.updatedAt)
    }

    @Test
    fun setStatusConfirmsAnItem() = runTest {
        seed(codes = listOf(code("c1", "A")))

        repo.setStatus(ItemKind.ERROR_CODE, "c1", ReviewStatus.CONFIRMED)

        val row = db.errorCodeDao().findById("c1")!!
        assertEquals(ReviewStatus.CONFIRMED, row.status)
        assertEquals(now, row.updatedAt)
        assertEquals(emptyList<ReviewItem>(), repo.suggestedFor("A"))
    }

    @Test
    fun setStatusOfAMissingIdDoesNothing() = runTest {
        seed(codes = listOf(code("c1", "A")), tasks = listOf(task("t1", "A")))

        repo.setStatus(ItemKind.ERROR_CODE, "missing", ReviewStatus.CONFIRMED)
        repo.setStatus(ItemKind.MAINTENANCE_TASK, "missing", ReviewStatus.REJECTED)
        // An id that exists under the other kind is also "missing" for this kind.
        repo.setStatus(ItemKind.MAINTENANCE_TASK, "c1", ReviewStatus.REJECTED)

        assertEquals(listOf(code("c1", "A")), db.errorCodeDao().getAll())
        assertEquals(listOf(task("t1", "A")), db.maintenanceTaskDao().getAll())
    }

    @Test
    fun suggestedForLabelsItemsForDisplay() = runTest {
        seed(
            codes = listOf(code("c1", "A", code = "e-21", meaning = "Door zone fault", page = 4)),
            tasks = listOf(task("t1", "A", name = "Check brake", months = 3, page = 9)),
        )

        val items = repo.suggestedFor("A")

        assertEquals(
            listOf(
                ReviewItem(ItemKind.ERROR_CODE, "c1", "e-21", "Door zone fault", 4),
                ReviewItem(ItemKind.MAINTENANCE_TASK, "t1", "Check brake", "every 3 months", 9),
            ),
            items,
        )
    }

    @Test
    fun suggestedForSortsByPageThenLabelAcrossBothKinds() = runTest {
        seed(
            codes = listOf(
                code("c-p5-e30", "A", code = "E30", page = 5),
                code("c-p2-e10", "A", code = "E10", page = 2),
                code("c-p5-e02", "A", code = "E02", page = 5),
            ),
            tasks = listOf(
                task("t-p5-lube", "A", name = "Lube", page = 5),
                task("t-p1-oil", "A", name = "Oil", page = 1),
                task("t-p2-adjust", "A", name = "Adjust", page = 2),
            ),
        )

        val ids = repo.suggestedFor("A").map { it.id }

        assertEquals(
            listOf("t-p1-oil", "t-p2-adjust", "c-p2-e10", "c-p5-e02", "c-p5-e30", "t-p5-lube"),
            ids,
        )
    }

    @Test
    fun suggestedForReturnsOnlySuggestedItemsOfThatManual() = runTest {
        seed(
            codes = listOf(
                code("c1", "A"),
                code("c2", "A", status = ReviewStatus.CONFIRMED),
                code("c3", "A", status = ReviewStatus.REJECTED),
                code("c4", "B"),
            ),
            tasks = listOf(
                task("t1", "A"),
                task("t2", "A", status = ReviewStatus.CONFIRMED),
                task("t3", "B"),
            ),
        )

        val ids = repo.suggestedFor("A").map { it.id }.toSet()

        assertEquals(setOf("c1", "t1"), ids)
    }

    @Test
    fun suggestedCountCountsCodesAndTasksOfThatManual() = runTest {
        seed(
            codes = listOf(
                code("c1", "A"),
                code("c2", "A"),
                code("c3", "A", status = ReviewStatus.REJECTED),
                code("c4", "B"),
            ),
            tasks = listOf(
                task("t1", "A"),
                task("t2", "A", status = ReviewStatus.CONFIRMED),
                task("t3", "B"),
            ),
        )

        assertEquals(3, repo.suggestedCount("A"))
        assertEquals(2, repo.suggestedCount("B"))
        assertEquals(0, repo.suggestedCount("none"))
        assertTrue(repo.suggestedFor("none").isEmpty())
    }
}
