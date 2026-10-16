package com.elevman.ui.elevators

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elevman.AppContainer
import com.elevman.ui.ScreenTopBar

/**
 * The Elevators tab: the page on top of the view model's stack (the list, an elevator, or the form
 * for a new one), and the message that says why a change was not saved. The tab bar stays; the
 * pages below the list have their own Back, and so has the system Back.
 */
@Composable
fun ElevatorsRoute(container: AppContainer, modifier: Modifier = Modifier) {
    val viewModel: ElevatorViewModel = viewModel(
        factory = ElevatorViewModel.factory(container.db, container.elevators, container.clock),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Rows can change while the tab is away (a backup import on another tab).
    LaunchedEffect(viewModel) { viewModel.reload() }

    BackHandler(enabled = state.pages.size > 1) { viewModel.back() }

    when (state.page) {
        ElevatorPage.Overview -> ElevatorsScreen(
            state = state,
            onOpen = viewModel::openElevator,
            onAdd = viewModel::openAddElevator,
            modifier = modifier,
        )
        is ElevatorPage.Detail -> Column(modifier = modifier.fillMaxSize()) {
            ScreenTopBar(title = state.detail?.site ?: "Elevator", onBack = viewModel::back)
            ElevatorDetailScreen(
                detail = state.detail,
                loaded = state.detailLoaded,
                today = viewModel::today,
                onMarkDone = viewModel::markDone,
                onAddNote = viewModel::addNote,
                onAssign = viewModel::assignTask,
            )
        }
        ElevatorPage.AddElevator -> Column(modifier = modifier.fillMaxSize()) {
            ScreenTopBar(title = "Add elevator", onBack = viewModel::back)
            AddElevatorScreen(models = state.models, busy = state.busy, onSave = viewModel::addElevator)
        }
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
        )
    }
}
