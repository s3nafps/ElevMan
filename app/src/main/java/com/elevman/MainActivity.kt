package com.elevman

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.elevman.ui.MainScaffold
import com.elevman.ui.theme.ElevManTheme

class MainActivity : ComponentActivity() {

    // The PDF another app shared, until the import has taken it over.
    private var incomingPdf by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system bars (Android 15 and later force this anyway); MainScaffold keeps
        // the content out from under them.
        enableEdgeToEdge()
        // A recreated activity (a rotation) gets the same intent again; the PDF was taken the first time.
        if (savedInstanceState == null) takeSharedPdf(intent)
        val container = (application as ElevManApp).container
        setContent {
            ElevManTheme {
                MainScaffold(container, incomingPdf, onIncomingPdfHandled = { incomingPdf = null })
            }
        }
    }

    // The activity is singleTop, so a PDF shared while it is on screen arrives here, not in a second
    // copy of the app. It goes through the same handler as a PDF shared at start-up.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // getIntent() is the latest intent, as for any activity that can be started again while open.
        setIntent(intent)
        takeSharedPdf(intent)
    }

    /**
     * Hands the PDF that [intent] carries, if any, to the import step. Used for the intent that starts
     * the activity and for every one that arrives later. Returns whether [intent] carried a PDF; an
     * intent without one (a tap on the launcher icon) changes nothing.
     */
    internal fun takeSharedPdf(intent: Intent): Boolean {
        val pdf = pdfFrom(intent) ?: return false
        incomingPdf = pdf
        return true
    }
}

/** The PDF an intent carries: the data of a VIEW, or the stream of a SEND. Null for anything else. */
internal fun pdfFrom(intent: Intent?): Uri? = when (intent?.action) {
    Intent.ACTION_VIEW -> intent.data
    Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    else -> null
}
