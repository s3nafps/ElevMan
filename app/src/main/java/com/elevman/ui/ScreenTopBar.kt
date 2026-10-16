package com.elevman.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * The bar at the top of a screen that sits below a tab (a model, a review, an import): a Back
 * button and the screen's [title], which is its heading. [onBack] goes one screen back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    TopAppBar(
        modifier = modifier,
        navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
        title = { Text(text = title, modifier = Modifier.semantics { heading() }) },
    )
}
