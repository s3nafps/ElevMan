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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
    private fun share(uri: Uri) = deliver(sendIntent(uri))

    private fun sendIntent(uri: Uri) =
        Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)

    private fun deliver(intent: Intent) {
        rule.runOnUiThread {
            val method = MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            method.isAccessible = true
            method.invoke(rule.activity, intent)
        }
    }

    // Robolectric's ActivityScenario finds the activity again by its launch intent when it closes it,
    // and waits forever if setIntent has replaced that intent. The app is right to replace it, so each
    // test puts the launch intent back before the rule closes the scenario.
    private lateinit var launchIntent: Intent

    @Before
    fun rememberLaunchIntent() {
        launchIntent = rule.activity.intent
    }

    @After
    fun putTheLaunchIntentBack() {
        rule.runOnUiThread { rule.activity.intent = launchIntent }
    }

    private fun pdfFromIntent(intent: Intent): Uri? =
        androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)

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
    fun theSharedIntentBecomesTheIntentOfTheActivity() {
        val uri = Uri.parse("content://test/shared.pdf")

        share(uri)

        // setIntent: getIntent() is the share, not the intent that started the activity.
        assertEquals(Intent.ACTION_SEND, rule.activity.intent.action)
        assertEquals(uri, pdfFromIntent(rule.activity.intent))
        waitForImportStep()
    }

    @Test
    fun aLauncherTapOnTheRunningAppDoesNotOpenTheImportStep() {
        deliver(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        rule.waitForIdle()

        rule.onNodeWithText("Import manual").assertDoesNotExist()
    }

    @Test
    fun theHandlerBothPathsUseHandsASharedPdfToTheImportStep() {
        // The function onCreate and onNewIntent both call, without going through onNewIntent.
        var taken = false
        rule.runOnUiThread { taken = rule.activity.takeSharedPdf(sendIntent(Uri.parse("content://test/direct.pdf"))) }

        assertTrue(taken)
        waitForImportStep()
        rule.onNodeWithText("Title").assertIsDisplayed()
    }

    @Test
    fun theHandlerIgnoresAnIntentWithoutAPdf() {
        var taken = true
        rule.runOnUiThread { taken = rule.activity.takeSharedPdf(Intent(Intent.ACTION_MAIN)) }
        rule.waitForIdle()

        assertFalse(taken)
        rule.onNodeWithText("Import manual").assertDoesNotExist()
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
