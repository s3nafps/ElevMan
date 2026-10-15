package com.elevman.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elevman.AppContainer

/**
 * The app's frame: one screen at a time, and a bottom bar to switch between the four [Tab]s.
 *
 * The scaffold keeps its content out of the system bars (status bar, gesture or button bar,
 * display cutout): [Scaffold] reports them in the padding it hands to the content, and the bar
 * pads itself for the bottom one. The content must apply that padding.
 */
@Composable
fun MainScaffold(
    // Not read yet: the screens that replace the placeholders get their repositories from here.
    @Suppress("UNUSED_PARAMETER") container: AppContainer,
) {
    var selected by rememberSaveable { mutableStateOf(Tab.SEARCH) }

    Scaffold(
        bottomBar = {
            // Items keep the NavigationBar default size, which is well over 48dp to tap.
            NavigationBar {
                for (tab in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = { selected = tab },
                        // The label names the item for a screen reader, the icon only decorates.
                        icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { innerPadding ->
        TabPlaceholder(tab = selected, modifier = Modifier.padding(innerPadding))
    }
}

/** A stand-in screen: just the tab's label as a title. Later tasks replace it. */
@Composable
private fun TabPlaceholder(tab: Tab, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        Text(
            text = tab.label,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .padding(16.dp)
                .semantics { heading() },
        )
    }
}
