package com.elevman.ui.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elevman.AppContainer
import com.elevman.ui.ScreenTopBar

/**
 * The review of manual [manualId]: its suggestions beside their pages, and the edit dialog when
 * one is being corrected. [onOpenPage] opens the manual in the viewer at a page; [onBack] leaves.
 */
@Composable
fun ReviewRoute(
    manualId: String,
    container: AppContainer,
    onOpenPage: (manualId: String, page: Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ReviewViewModel = viewModel(
        key = "review-$manualId",
        factory = ReviewViewModel.factory(
            manualId = manualId,
            review = container.review,
            codes = container.db.errorCodeDao(),
            tasks = container.db.maintenanceTaskDao(),
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Rows can change while the screen is away (a backup import on another tab).
    LaunchedEffect(viewModel) { viewModel.refresh() }
    val file = remember(manualId) { container.manualFile(manualId) }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTopBar(title = "Review", onBack = onBack)
        if (!state.loaded) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator()
            }
        } else {
            ReviewScreen(
                items = state.items,
                onAccept = viewModel::accept,
                onEdit = viewModel::startEdit,
                onReject = viewModel::reject,
                onAcceptAll = viewModel::acceptAll,
                thumbnail = { item -> PageThumbnail(file = file, pageNumber = item.pageNumber) },
                onOpenPage = { item -> onOpenPage(manualId, item.pageNumber) },
                // While the dialog is open the message is shown in it, not behind it.
                message = state.message.takeIf { state.editing == null },
            )
        }
    }

    state.editing?.let { target ->
        EditDialog(
            target = target,
            onSave = viewModel::save,
            onDismiss = viewModel::dismissEdit,
            error = state.message,
        )
    }
}
