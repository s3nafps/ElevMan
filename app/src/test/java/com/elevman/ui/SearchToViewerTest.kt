package com.elevman.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.ElevManApp
import com.elevman.MainActivity
import com.elevman.data.ElevatorModelEntity
import com.elevman.data.ErrorCodeEntity
import com.elevman.data.ReviewStatus
import com.elevman.domain.normalizeCode
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Search tab opens the PDF viewer as a full-screen destination and comes back from it.
 *
 * The PDF the result points at is not on the phone, so the viewer shows its "missing" message.
 * That keeps the real PdfRenderer, which needs a device, out of these tests.
 */
@RunWith(AndroidJUnit4::class)
class SearchToViewerTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val missingMessage = "This manual's PDF is missing from the phone."

    @Before
    fun seedOneCode() {
        val container = (rule.activity.application as ElevManApp).container
        runBlocking {
            container.db.elevatorModelDao().upsertAll(
                listOf(ElevatorModelEntity("m-a", "maker-1", "Alpha 100", "", 1_000L)),
            )
            container.db.errorCodeDao().upsertAll(
                listOf(
                    ErrorCodeEntity(
                        id = "c-1",
                        modelId = "m-a",
                        manualId = "manual-gone",
                        code = "E-21",
                        normalizedCode = normalizeCode("E-21"),
                        meaning = "Door fault",
                        causeAndFix = "",
                        pageNumber = 7,
                        status = ReviewStatus.CONFIRMED,
                        updatedAt = 1_000L,
                    ),
                ),
            )
        }
    }

    private fun searchAndOpenTheResult() {
        rule.onNode(hasSetTextAction()).performTextInput("e21")
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText("Door fault").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Door fault").performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText(missingMessage).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tappingAResultOpensTheViewerInPlaceOfTheTabs() {
        searchAndOpenTheResult()

        rule.onNodeWithText(missingMessage).assertIsDisplayed()
        // Full screen: the bottom bar is gone, and so is the search screen.
        for (tab in Tab.entries) rule.onAllNodesWithText(tab.label).assertCountEquals(0)
        rule.onAllNodesWithText("Door fault").assertCountEquals(0)
    }

    @Test
    fun theViewerBackButtonReturnsToTheResults() {
        searchAndOpenTheResult()

        rule.onNodeWithText("Back").performClick()

        rule.onNodeWithText("Door fault").assertIsDisplayed()
        rule.onAllNodesWithText(missingMessage).assertCountEquals(0)
    }

    @Test
    fun theSystemBackReturnsToTheResultsToo() {
        searchAndOpenTheResult()

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

        rule.onNodeWithText("Door fault").assertIsDisplayed()
        rule.onAllNodesWithText(missingMessage).assertCountEquals(0)
    }

    @Test
    fun theOpenViewerSurvivesTheActivityBeingRecreated() {
        searchAndOpenTheResult()

        rule.activityRule.scenario.recreate()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText(missingMessage).fetchSemanticsNodes().isNotEmpty()
        }

        rule.onNodeWithText(missingMessage).assertIsDisplayed()
    }

    @Test
    fun theQueryIsStillThereAfterTheViewerIsClosed() {
        searchAndOpenTheResult()

        rule.onNodeWithText("Back").performClick()

        rule.onNode(hasSetTextAction()).assertIsDisplayed()
        rule.onAllNodesWithText("e21").assertCountEquals(1)
    }
}
