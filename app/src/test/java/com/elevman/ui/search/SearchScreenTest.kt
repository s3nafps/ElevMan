package com.elevman.ui.search

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.data.CodeHit
import com.elevman.data.ReviewStatus
import com.elevman.ui.theme.ElevManTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun hit(
        code: String = "E-21",
        meaning: String = "Door fault",
        modelName: String = "Alpha 100",
        manualId: String = "manual-1",
        pageNumber: Int = 12,
        status: ReviewStatus = ReviewStatus.CONFIRMED,
    ) = CodeHit(code, meaning, modelName, manualId, pageNumber, status)

    private fun show(
        state: SearchUiState,
        onQueryChange: (String) -> Unit = {},
        onOpen: (String, Int) -> Unit = { _, _ -> },
    ) {
        rule.setContent {
            ElevManTheme { SearchScreen(state, onQueryChange, onOpen) }
        }
    }

    @Test
    fun suggestedResultShowsLabel() {
        val suggested = hit(meaning = "Door fault", status = ReviewStatus.SUGGESTED)
        show(SearchUiState("e21", listOf("Alpha 100" to listOf(suggested))))

        rule.onNodeWithText("Suggested, not yet checked").assertIsDisplayed()
    }

    @Test
    fun confirmedResultShowsNoLabel() {
        show(SearchUiState("e21", listOf("Alpha 100" to listOf(hit()))))

        rule.onNodeWithText("Door fault").assertIsDisplayed()
        rule.onNodeWithText("Suggested, not yet checked").assertDoesNotExist()
    }

    @Test
    fun onlyTheSuggestedResultOfTwoGetsTheLabel() {
        val confirmed = hit(meaning = "Door fault")
        val suggested = hit(meaning = "Door lock fault", manualId = "manual-2", status = ReviewStatus.SUGGESTED)
        show(SearchUiState("e21", listOf("Alpha 100" to listOf(confirmed, suggested))))

        rule.onAllNodesWithText("Suggested, not yet checked").assertCountEquals(1)
    }

    @Test
    fun groupHeadingsAndResultsAreShown() {
        show(
            SearchUiState(
                "e21",
                listOf(
                    "Alpha 100" to listOf(hit(meaning = "Door fault")),
                    "Beta 200" to listOf(hit(meaning = "Overspeed", modelName = "Beta 200", manualId = "manual-2")),
                ),
            ),
        )

        rule.onNodeWithText("Alpha 100").assertIsDisplayed()
        rule.onNodeWithText("Beta 200").assertIsDisplayed()
        rule.onNodeWithText("Door fault").assertIsDisplayed()
        rule.onNodeWithText("Overspeed").assertIsDisplayed()
    }

    @Test
    fun aResultNamesItsCodeAndPage() {
        show(SearchUiState("e21", listOf("Alpha 100" to listOf(hit(code = "E-21", pageNumber = 12)))))

        rule.onNodeWithText("E-21", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Page 12", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingAResultOpensItsManualAtItsPage() {
        val opened = mutableListOf<Pair<String, Int>>()
        show(
            SearchUiState(
                "e21",
                listOf("Alpha 100" to listOf(hit(meaning = "Door fault", manualId = "manual-7", pageNumber = 33))),
            ),
            onOpen = { manualId, page -> opened += manualId to page },
        )

        rule.onNodeWithText("Door fault").performClick()

        assertEquals(listOf("manual-7" to 33), opened)
    }

    @Test
    fun typingReportsTheNewQuery() {
        val queries = mutableListOf<String>()
        show(SearchUiState("", emptyList()), onQueryChange = { queries += it })

        rule.onNode(hasSetTextAction()).performTextInput("e21")

        assertEquals(listOf("e21"), queries)
    }

    @Test
    fun theQueryInTheStateIsInTheField() {
        show(SearchUiState("E-21", emptyList()))

        rule.onNode(hasSetTextAction()).assert(hasText("E-21"))
    }

    @Test
    fun aQueryWithNoResultsSaysSo() {
        show(SearchUiState("zz99", emptyList()))

        rule.onNodeWithText("No error code matches", substring = true).assertIsDisplayed()
    }

    @Test
    fun noMessageWhileTheSearchIsStillRunning() {
        show(SearchUiState("zz99", emptyList(), isSearching = true))

        rule.onNodeWithText("No error code matches", substring = true).assertDoesNotExist()
    }

    @Test
    fun anEmptyQueryShowsAHintNotAMessageAboutResults() {
        show(SearchUiState("", emptyList()))

        rule.onNodeWithText("No error code matches", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Type an error code", substring = true).assertIsDisplayed()
    }
}
