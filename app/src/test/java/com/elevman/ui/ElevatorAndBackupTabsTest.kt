package com.elevman.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.ElevManApp
import com.elevman.MainActivity
import com.elevman.data.ElevatorModelEntity
import com.elevman.data.MaintenanceTaskEntity
import com.elevman.data.ManufacturerEntity
import com.elevman.data.ReviewStatus
import com.elevman.ui.elevators.ELEVATORS_EMPTY_MESSAGE
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Elevators and Backup tabs inside the real app: the container, the view models and the screens
 * together, against the app's own database. A tall screen, so that the whole elevator page is on
 * screen: a lazy list only builds what is visible.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h1200dp")
class ElevatorAndBackupTabsTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun openTab(tab: Tab) {
        rule.onAllNodesWithText(tab.label).filterToOne(hasClickAction()).performClick()
    }

    private fun waitForText(text: String, substring: Boolean = false) = rule.waitUntil(timeoutMillis = 10_000) {
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun seedModelWithAConfirmedTask() {
        val db = (rule.activity.application as ElevManApp).container.db
        runBlocking {
            db.manufacturerDao().upsertAll(listOf(ManufacturerEntity("mk-1", "Otis", 1_000L)))
            db.elevatorModelDao().upsertAll(listOf(ElevatorModelEntity("m-gen2", "mk-1", "Gen2", "", 1_000L)))
            db.maintenanceTaskDao().upsertAll(
                listOf(MaintenanceTaskEntity("t-brake", "m-gen2", "man-1", "Check brake", 6, 4, ReviewStatus.CONFIRMED, 1_000L)),
            )
        }
    }

    @Test
    fun anElevatorIsAddedGivenATaskAndMarkedDone() {
        seedModelWithAConfirmedTask()
        openTab(Tab.ELEVATORS)
        waitForText(ELEVATORS_EMPTY_MESSAGE)

        // Add elevator: the model is picked from the library's models.
        rule.onNodeWithText("Add elevator").performClick()
        rule.onNode(hasText("Site") and hasSetTextAction()).performTextInput("Harbour office")
        rule.onNodeWithText("Otis Gen2").performScrollTo().performClick()
        rule.onNodeWithText("Save").performScrollTo().performClick()
        waitForText("Harbour office")
        rule.onNodeWithText("Otis Gen2").performClick()

        // Assign task: the model's confirmed task, due one interval after today.
        waitForText("Assign task")
        rule.onNodeWithText("Assign task").performClick()
        rule.onNodeWithText("Check brake", substring = true).performClick()
        val today = LocalDate.now()
        waitForText("Due ${today.plusMonths(6)}")

        // Mark done: the date picker starts on today, and the entry is logged.
        rule.onNodeWithText("Mark done").performClick()
        rule.onNodeWithText("OK").performClick()
        waitForText("$today · Check brake")
        rule.onNodeWithText("Due ${today.plusMonths(6)}").assertIsDisplayed()
    }

    @Test
    fun theBackupTabShowsTheSizeAndOffersShareAndImport() {
        openTab(Tab.BACKUP)

        // An empty phone: the file is only a few hundred bytes, which rounds to 0 MB.
        waitForText("Backup is about 0 MB")
        rule.onNodeWithText("Share backup").assertIsEnabled()
        rule.onNodeWithText("Import backup").assertIsEnabled()
    }
}
