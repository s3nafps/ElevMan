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
        if (savedInstanceState == null) incomingPdf = pdfFrom(intent)
        val container = (application as ElevManApp).container
        setContent {
            ElevManTheme {
                MainScaffold(container, incomingPdf, onIncomingPdfHandled = { incomingPdf = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pdfFrom(intent)?.let { incomingPdf = it }
    }
}

/** The PDF an intent carries: the data of a VIEW, or the stream of a SEND. Null for anything else. */
internal fun pdfFrom(intent: Intent?): Uri? = when (intent?.action) {
    Intent.ACTION_VIEW -> intent.data
    Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    else -> null
}
