package com.elevman.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.elevman.AppContainer
import com.elevman.ui.backup.BackupRoute
import com.elevman.ui.elevators.ElevatorsRoute
import com.elevman.ui.library.LibraryRoute
import com.elevman.ui.library.LibraryViewModel
import com.elevman.ui.search.SearchScreen
import com.elevman.ui.search.SearchViewModel
import com.elevman.ui.viewer.PdfViewerScreen

/**
 * The app's frame: one screen at a time, and a bottom bar to switch between the four [Tab]s.
 *
 * The PDF viewer is not a tab. Opening a search result replaces the whole frame, bottom bar
 * included, with the viewer; its back action (or the system back) returns to the tab that was
 * open. Which manual and page the viewer shows is saved with the rest of the state, so it
 * survives the activity being recreated.
 *
 * Review and Import, which the Library tab opens, are full-screen too: while one is on top, the
 * bar gives way to the screen's own Back. [incomingPdf] is a PDF another app shared with ElevMan;
 * it opens the Library tab's import, and [onIncomingPdfHandled] is called once it has.
 *
 * The scaffold keeps its content out of the system bars (status bar, gesture or button bar,
 * display cutout): [Scaffold] reports them in the padding it hands to the content, and the bar
 * pads itself for the bottom one. The content must apply that padding.
 */
@Composable
fun MainScaffold(
    container: AppContainer,
    incomingPdf: Uri? = null,
    onIncomingPdfHandled: () -> Unit = {},
) {
    var selected by rememberSaveable { mutableStateOf(Tab.SEARCH) }
    var viewer by rememberSaveable(stateSaver = ViewerTargetSaver) { mutableStateOf<ViewerTarget?>(null) }
    val searchViewModel: SearchViewModel =
        viewModel(factory = SearchViewModel.factory(container.search))

    val libraryViewModel: LibraryViewModel =
        viewModel(factory = LibraryViewModel.factory(container.db, container.review))
    val libraryState by libraryViewModel.state.collectAsStateWithLifecycle()

    // The Library is read again whenever its tab is opened: a backup import on another tab can have
    // changed it, and the view model outlives the tab.
    LaunchedEffect(selected) {
        if (selected == Tab.LIBRARY) libraryViewModel.reload()
    }

    // A shared PDF is for the Library, whatever is open now.
    LaunchedEffect(incomingPdf) {
        if (incomingPdf != null) {
            viewer = null
            selected = Tab.LIBRARY
        }
    }

    val target = viewer
    if (target != null) {
        BackHandler { viewer = null }
        PdfViewerScreen(
            manualId = target.manualId,
            startPage = target.page,
            container = container,
            onBack = { viewer = null },
        )
    } else {
        Scaffold(
            bottomBar = {
                // The Library's full-screen pages (review, import) have their own Back instead.
                if (!(selected == Tab.LIBRARY && libraryState.hidesTabBar)) {
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
                }
            },
        ) { innerPadding ->
            // The padding has already kept the content clear of the bars; saying so stops a screen
            // that pads for the keyboard from adding the bottom bar's height a second time.
            val contentModifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)
            when (selected) {
                Tab.SEARCH -> {
                    val state by searchViewModel.state.collectAsStateWithLifecycle()
                    SearchScreen(
                        state = state,
                        onQueryChange = searchViewModel::onQueryChange,
                        onOpen = { manualId, page -> viewer = ViewerTarget(manualId, page) },
                        modifier = contentModifier,
                    )
                }
                Tab.LIBRARY -> LibraryRoute(
                    container = container,
                    library = libraryViewModel,
                    incomingPdf = incomingPdf,
                    onIncomingPdfHandled = onIncomingPdfHandled,
                    onOpenViewer = { manualId, page -> viewer = ViewerTarget(manualId, page) },
                    modifier = contentModifier,
                )
                Tab.ELEVATORS -> ElevatorsRoute(container = container, modifier = contentModifier)
                Tab.BACKUP -> BackupRoute(container = container, modifier = contentModifier)
            }
        }
    }
}

/** The manual and page the PDF viewer is open on. */
private data class ViewerTarget(val manualId: String, val page: Int)

// A closed viewer is null, which is not saved, so it comes back closed.
private val ViewerTargetSaver: Saver<ViewerTarget?, Any> = Saver(
    save = { target -> target?.let { listOf(it.manualId, it.page) } },
    restore = { saved ->
        val (manualId, page) = saved as List<*>
        ViewerTarget(manualId as String, page as Int)
    },
)
