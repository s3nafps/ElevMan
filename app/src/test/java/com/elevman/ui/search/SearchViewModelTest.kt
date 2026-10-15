package com.elevman.ui.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.data.CodeSearch
import com.elevman.data.ElevManDatabase
import com.elevman.data.ElevatorModelEntity
import com.elevman.data.ErrorCodeEntity
import com.elevman.data.ReviewStatus
import com.elevman.domain.normalizeCode
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the view model on real threads against an in-memory database, because the search is
 * meant to run on [Dispatchers.IO]. Every wait has a timeout, so a bug fails instead of hanging.
 */
@RunWith(AndroidJUnit4::class)
class SearchViewModelTest {

    private lateinit var db: ElevManDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun viewModel() = SearchViewModel(CodeSearch(db), scope)

    /** Waits until the state is for [query] and its search has finished. */
    private fun SearchViewModel.settled(query: String): SearchUiState = runBlocking {
        withTimeout(10.seconds) { state.first { it.query == query && !it.isSearching } }
    }

    private fun seedModels(vararg names: Pair<String, String>) = runBlocking {
        db.elevatorModelDao().upsertAll(
            names.map { (id, name) ->
                ElevatorModelEntity(
                    id = id,
                    manufacturerId = "maker-1",
                    name = name,
                    notes = "",
                    updatedAt = 1_000L,
                )
            },
        )
    }

    private fun seedCode(
        id: String,
        modelId: String,
        code: String,
        meaning: String,
        status: ReviewStatus = ReviewStatus.CONFIRMED,
    ) = runBlocking {
        db.errorCodeDao().upsertAll(
            listOf(
                ErrorCodeEntity(
                    id = id,
                    modelId = modelId,
                    manualId = "manual-$modelId",
                    code = code,
                    normalizedCode = normalizeCode(code),
                    meaning = meaning,
                    causeAndFix = "",
                    pageNumber = 5,
                    status = status,
                    updatedAt = 1_000L,
                ),
            ),
        )
    }

    @Test
    fun startsWithAnEmptyQueryAndNoGroups() {
        assertEquals(SearchUiState("", emptyList()), viewModel().state.value)
    }

    @Test
    fun theQueryShowsInTheStateAtOnce() {
        val viewModel = viewModel()

        viewModel.onQueryChange("e2")

        // No waiting: the text field reads this value right after the key press.
        assertEquals("e2", viewModel.state.value.query)
    }

    @Test
    fun resultsAreGroupedByModelInTheOrderCodeSearchReturnedThem() {
        seedModels("m-a" to "Alpha 100", "m-b" to "Beta 200")
        // CodeSearch puts confirmed hits first, so it returns Alpha (confirmed), Beta (confirmed),
        // then Alpha (suggested). The groups follow that order, they are not re-sorted.
        seedCode("1", "m-a", "E-21", "Door fault A", ReviewStatus.CONFIRMED)
        seedCode("2", "m-b", "E-21", "Door fault B", ReviewStatus.CONFIRMED)
        seedCode("3", "m-a", "E21", "Door fault A again", ReviewStatus.SUGGESTED)
        val viewModel = viewModel()

        viewModel.onQueryChange("e 21")
        val groups = viewModel.settled("e 21").groups

        assertEquals(listOf("Alpha 100", "Beta 200"), groups.map { it.first })
        assertEquals(listOf("Door fault A", "Door fault A again"), groups[0].second.map { it.meaning })
        assertEquals(listOf("Door fault B"), groups[1].second.map { it.meaning })
    }

    @Test
    fun aGroupThatFirstAppearsLaterInTheResultsComesLater() {
        seedModels("m-a" to "Alpha 100", "m-b" to "Beta 200")
        // Beta has the confirmed hit, Alpha only a suggested one, so Beta is first although it is
        // later in the alphabet.
        seedCode("1", "m-b", "E-21", "Door fault B", ReviewStatus.CONFIRMED)
        seedCode("2", "m-a", "E-21", "Door fault A", ReviewStatus.SUGGESTED)
        val viewModel = viewModel()

        viewModel.onQueryChange("E-21")

        assertEquals(listOf("Beta 200", "Alpha 100"), viewModel.settled("E-21").groups.map { it.first })
    }

    @Test
    fun aBlankQueryGivesEmptyGroups() {
        seedModels("m-a" to "Alpha 100")
        seedCode("1", "m-a", "E-21", "Door fault")
        val viewModel = viewModel()
        viewModel.onQueryChange("E-21")
        assertEquals(1, viewModel.settled("E-21").groups.size)

        viewModel.onQueryChange("   ")

        val state = viewModel.settled("   ")
        assertEquals(emptyList<Pair<String, List<*>>>(), state.groups)
    }

    @Test
    fun aNewQueryReplacesTheResultsOfTheOldOne() {
        seedModels("m-a" to "Alpha 100")
        seedCode("1", "m-a", "E-21", "Door fault")
        seedCode("2", "m-a", "E-22", "Overspeed")
        val viewModel = viewModel()
        viewModel.onQueryChange("E-21")
        assertEquals("Door fault", viewModel.settled("E-21").groups.single().second.single().meaning)

        viewModel.onQueryChange("E-22")

        assertEquals("Overspeed", viewModel.settled("E-22").groups.single().second.single().meaning)
    }

    @Test
    fun theLastQueryWinsWhenTheyComeInQuickSuccession() {
        seedModels("m-a" to "Alpha 100")
        seedCode("1", "m-a", "E-21", "Door fault")
        seedCode("2", "m-a", "E-22", "Overspeed")
        val viewModel = viewModel()

        viewModel.onQueryChange("E-21")
        viewModel.onQueryChange("E-22")

        val state = viewModel.settled("E-22")
        assertEquals(listOf("Overspeed"), state.groups.flatMap { it.second }.map { it.meaning })
    }

    @Test
    fun aQueryWithNoMatchGivesEmptyGroupsAndStopsSearching() {
        seedModels("m-a" to "Alpha 100")
        seedCode("1", "m-a", "E-21", "Door fault")
        val viewModel = viewModel()

        viewModel.onQueryChange("Z-99")

        val state = viewModel.settled("Z-99")
        assertEquals(emptyList<Pair<String, List<*>>>(), state.groups)
    }
}
