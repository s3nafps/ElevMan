package com.elevman.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A PDF shared with the app while it is already running goes to the same import step as a picked one. */
@RunWith(AndroidJUnit4::class)
class SharedPdfTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    // What Android does with a singleTop activity that is already on top. onNewIntent is protected, so
    // it is called the way the system calls it.
    private fun share(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
        rule.runOnUiThread {
            val method = MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            method.isAccessible = true
            method.invoke(rule.activity, send)
        }
    }

    private fun waitForImportStep() {
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText("Import manual").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun SemanticsNodeInteraction.clickAndSettle() {
        performClick()
        rule.waitForIdle()
    }

    @Test
    fun aPdfSharedToTheRunningAppOpensTheImportStep() {
        share(Uri.parse("content://test/shared.pdf"))

        waitForImportStep()
        rule.onNodeWithText("Import manual").assertIsDisplayed()
        rule.onNodeWithText("Title").assertIsDisplayed()
    }

    @Test
    fun aSecondPdfSharedAfterTheFirstWasLeftOpensTheImportStepAgain() {
        share(Uri.parse("content://test/one.pdf"))
        waitForImportStep()
        rule.onNodeWithText("Back").clickAndSettle()
        rule.onNodeWithText("Import manual").assertDoesNotExist()

        share(Uri.parse("content://test/two.pdf"))

        waitForImportStep()
        rule.onNodeWithText("Title").assertIsDisplayed()
    }
}
