package com.elevman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.elevman.ui.MainScaffold
import com.elevman.ui.theme.ElevManTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system bars (Android 15 and later force this anyway); MainScaffold keeps
        // the content out from under them.
        enableEdgeToEdge()
        val container = (application as ElevManApp).container
        setContent {
            ElevManTheme {
                MainScaffold(container)
            }
        }
    }
}
