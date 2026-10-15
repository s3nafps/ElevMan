package com.elevman.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    // A tab's label shows twice once its screen is open: on the bar item and as the screen's
    // title. onAllNodesWithText finds both; the bar item is the one you can click, the title is
    // the heading.
    private fun tabItem(tab: Tab) = rule.onAllNodesWithText(tab.label).filterToOne(hasClickAction())

    private fun title(tab: Tab) = rule.onAllNodesWithText(tab.label).filterToOne(isHeading())

    @Test
    fun tappingEachTabShowsItsScreen() {
        // Reverse order, so every tap leaves the screen that was open (the app starts on Search).
        for (tab in Tab.entries.reversed()) {
            tabItem(tab).performClick()

            tabItem(tab).assertIsSelected()
            title(tab).assertIsDisplayed()
            // Only this tab's title is on screen, not the one of the screen that was left.
            rule.onAllNodes(isHeading()).assertCountEquals(1)
        }
    }

    @Test
    fun contentStaysClearOfTheSystemBars() {
        val statusBar = 96
        val gestureBar = 72
        rule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBar, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, gestureBar))
                .build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, insets)
        }
        rule.waitForIdle()

        val title = title(Tab.SEARCH).fetchSemanticsNode().boundsInWindow
        val bar = tabItem(Tab.SEARCH).fetchSemanticsNode().boundsInWindow
        assertTrue("the title ($title) starts under the status bar", title.top >= statusBar)
        assertTrue("the title ($title) runs into the bar ($bar)", title.bottom <= bar.top)
        val windowHeight = rule.activity.window.decorView.height
        assertTrue("the bar ($bar) is under the gesture bar", bar.bottom <= windowHeight - gestureBar)
    }
}
