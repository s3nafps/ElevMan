package com.elevman.ui.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elevman.AppContainer
import com.elevman.ui.ScreenTopBar
import com.elevman.ui.review.ReviewRoute

/**
 * The Library tab: the page on top of [library]'s stack, and the import that can start from the
 * Import button (the document picker) or from a PDF another app shared ([incomingPdf]).
 *
 * [onOpenViewer] opens a manual in the PDF viewer at a page.
 */
@Composable
fun LibraryRoute(
    container: AppContainer,
    library: LibraryViewModel,
    incomingPdf: Uri?,
    onIncomingPdfHandled: () -> Unit,
    onOpenViewer: (manualId: String, page: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val importViewModel: ImportViewModel = viewModel(
        factory = ImportViewModel.factory(
            importer = container.importer,
            db = container.db,
            pdfs = ContentResolverPdfSource(resolver),
            importLock = container.importLock,
        ),
    )
    val state by library.state.collectAsStateWithLifecycle()
    val importState by importViewModel.state.collectAsStateWithLifecycle()

    fun startImport(uri: Uri) {
        importViewModel.start(uri)
        library.openImport()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport(uri)
    }

    // A shared PDF goes to the same title and model step as a picked one.
    LaunchedEffect(incomingPdf) {
        if (incomingPdf != null) {
            startImport(incomingPdf)
            onIncomingPdfHandled()
        }
    }

    // An imported manual goes straight to its review, on top of its model's page.
    LaunchedEffect(importState.finished) {
        importState.finished?.let {
            library.showImported(it.modelId, it.manualId)
            importViewModel.reset()
        }
    }

    val leaveImport = {
        importViewModel.reset()
        library.back()
    }

    // The import is not interrupted: Back waits until it is done.
    BackHandler(enabled = state.pages.size > 1) {
        if (state.page == LibraryPage.Import) {
            if (!importState.working) leaveImport()
        } else {
            library.back()
        }
    }

    when (val page = state.page) {
        LibraryPage.Overview -> LibraryScreen(
            state = state,
            onOpenModel = library::openModel,
            onImport = { picker.launch(arrayOf("application/pdf")) },
            modifier = modifier,
        )
        is LibraryPage.Model -> Column(modifier = modifier.fillMaxSize()) {
            ScreenTopBar(title = state.detail?.takeIf { it.id == page.modelId }?.name ?: "Model", onBack = library::back)
            ModelScreen(
                detail = state.detail?.takeIf { it.id == page.modelId },
                loaded = state.detailLoaded,
                onOpenManual = onOpenViewer,
                onReview = library::openReview,
            )
        }
        is LibraryPage.Review -> ReviewRoute(
            manualId = page.manualId,
            container = container,
            onOpenPage = onOpenViewer,
            onBack = library::back,
            modifier = modifier,
        )
        LibraryPage.Import -> Column(modifier = modifier.fillMaxSize()) {
            ScreenTopBar(
                title = "Import manual",
                onBack = { if (!importState.working) leaveImport() },
            )
            ImportFlow(
                state = importState,
                onTitleChange = importViewModel::setTitle,
                onPickExisting = importViewModel::pickExisting,
                onPickNew = importViewModel::pickNew,
                onNewManufacturerChange = importViewModel::setNewManufacturer,
                onNewModelChange = importViewModel::setNewModel,
                onImport = importViewModel::import,
                onOpenInLibrary = { modelId ->
                    importViewModel.reset()
                    library.showModel(modelId)
                },
                onClose = leaveImport,
            )
        }
    }
}
