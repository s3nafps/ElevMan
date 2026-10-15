package com.elevman.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.elevman.AppContainer
import com.elevman.pdf.PageText
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The page is drawn this many times larger than the space it fills, so it stays sharp when zoomed.
private const val RENDER_SCALE = 2
private const val MAX_ZOOM = 4f

internal const val PDF_MISSING_MESSAGE = "This manual's PDF is missing from the phone."
internal const val PDF_UNREADABLE_MESSAGE = "Could not open this manual's PDF."
internal const val TEXT_FAILED_MESSAGE = "Could not read this manual's text."

/** How far the PDF has been opened. */
private sealed interface Document {
    data object Opening : Document
    data class Open(val pageCount: Int) : Document
    data object Missing : Document
    data object Unreadable : Document
}

/** How far the text of the pages has been read, for the search inside the manual. */
private sealed interface PageTexts {
    data object Loading : PageTexts
    data class Ready(val pages: List<PageText>) : PageTexts
    data object Failed : PageTexts
}

/**
 * Shows manual [manualId] from the phone's own storage, starting at page [startPage]: the page as
 * an image that can be pinched to zoom, buttons for the page before and after, and a search box
 * for words in the manual's text. [onBack] leaves the viewer.
 *
 * The PDF is opened and closed with the screen. Reading the text of every page takes a while, so
 * it runs in the background while the page is already shown; the search waits for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    manualId: String,
    startPage: Int,
    container: AppContainer,
    onBack: () -> Unit,
) {
    val file = remember(manualId) { container.manualFile(manualId) }

    // Opening the PDF.
    val pdf = remember(manualId) { PdfPageRenderer(file) }
    DisposableEffect(pdf) {
        onDispose { pdf.close() }
    }
    var document by remember(pdf) { mutableStateOf<Document>(Document.Opening) }
    LaunchedEffect(pdf) {
        document = try {
            val pageCount = pdf.open()
            if (pageCount > 0) Document.Open(pageCount) else Document.Unreadable
        } catch (e: FileNotFoundException) {
            Document.Missing
        } catch (e: IOException) {
            Document.Unreadable
        } catch (e: SecurityException) {
            // A password protected PDF.
            Document.Unreadable
        }
    }

    // The text of every page, read once and kept while the viewer is open.
    var texts by remember(manualId) { mutableStateOf<PageTexts>(PageTexts.Loading) }
    LaunchedEffect(manualId) {
        texts = try {
            PageTexts.Ready(withContext(Dispatchers.IO) { container.pageText.pages(file) })
        } catch (e: IOException) {
            PageTexts.Failed
        }
    }

    var page by rememberSaveable(manualId, startPage) { mutableIntStateOf(startPage) }
    var query by rememberSaveable(manualId) { mutableStateOf("") }

    // The pages that contain the query. Comparing is quick per page but there can be hundreds.
    var matches by remember(manualId) { mutableStateOf(emptyList<Int>()) }
    val readyPages = (texts as? PageTexts.Ready)?.pages
    LaunchedEffect(readyPages, query) {
        matches = if (readyPages == null) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) { findPages(readyPages, query) }
        }
    }

    val openDocument = document as? Document.Open
    val navigation = openDocument?.let { PageNavigation(it.pageCount, page, matches) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                title = {
                    val title = if (navigation == null) {
                        "Manual"
                    } else {
                        "Page ${navigation.currentPage} of ${navigation.pageCount}"
                    }
                    Text(text = title, modifier = Modifier.semantics { heading() })
                },
            )
        },
        bottomBar = {
            if (navigation != null) {
                BottomAppBar {
                    OutlinedButton(
                        onClick = { page = navigation.previousPage().currentPage },
                        enabled = navigation.hasPreviousPage,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    ) { Text("Previous page") }
                    Button(
                        onClick = { page = navigation.nextPage().currentPage },
                        enabled = navigation.hasNextPage,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    ) { Text("Next page") }
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (document) {
                Document.Opening -> Centered { CircularProgressIndicator() }
                Document.Missing -> Centered { Text(PDF_MISSING_MESSAGE, style = MaterialTheme.typography.bodyLarge) }
                Document.Unreadable -> Centered { Text(PDF_UNREADABLE_MESSAGE, style = MaterialTheme.typography.bodyLarge) }
                is Document.Open -> if (navigation != null) {
                    SearchInManual(
                        query = query,
                        onQueryChange = { query = it },
                        texts = texts,
                        navigation = navigation,
                        onGoTo = { page = it.currentPage },
                    )
                    PageImage(
                        pdf = pdf,
                        navigation = navigation,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) { content() }
}

/** The search box, what it found, and the buttons to move between the finds. */
@Composable
private fun SearchInManual(
    query: String,
    onQueryChange: (String) -> Unit,
    texts: PageTexts,
    navigation: PageNavigation,
    onGoTo: (PageNavigation) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val ready = texts is PageTexts.Ready
    val nextMatch = {
        // Put the keyboard away so the page it found is not behind it.
        keyboard?.hide()
        focus.clearFocus()
        onGoTo(navigation.nextMatch())
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            enabled = ready,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search in this manual") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { nextMatch() }),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            when (texts) {
                PageTexts.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("Reading the manual's text", style = MaterialTheme.typography.bodyMedium)
                }
                PageTexts.Failed -> Text(
                    TEXT_FAILED_MESSAGE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                is PageTexts.Ready -> Text(
                    text = matchSummary(query, navigation),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            if (ready) {
                val hasMatches = navigation.matches.isNotEmpty()
                TextButton(
                    onClick = { onGoTo(navigation.previousMatch()) },
                    enabled = hasMatches,
                ) { Text("Previous match") }
                TextButton(onClick = { nextMatch() }, enabled = hasMatches) { Text("Next match") }
            }
        }
    }
}

private fun matchSummary(query: String, navigation: PageNavigation): String {
    if (query.isBlank()) return ""
    val count = navigation.matches.size
    val index = navigation.matchIndex
    return when {
        count == 0 -> "No page contains \"${query.trim()}\""
        index != null -> "Match ${index + 1} of $count"
        count == 1 -> "1 page matches"
        else -> "$count pages match"
    }
}

/** The current page as an image, drawn off the main thread, with pinch to zoom and drag. */
@Composable
private fun PageImage(
    pdf: PdfPageRenderer,
    navigation: PageNavigation,
    modifier: Modifier = Modifier,
) {
    val pageNumber = navigation.currentPage
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight

        val bitmap by produceState<Bitmap?>(initialValue = null, pdf, pageNumber, widthPx, heightPx) {
            // The last page stays on screen until the next one is drawn.
            value = pdf.render(pageNumber, widthPx * RENDER_SCALE, heightPx * RENDER_SCALE)
        }

        // A new page starts unzoomed.
        var zoom by remember(pageNumber) { mutableFloatStateOf(1f) }
        var pan by remember(pageNumber) { mutableStateOf(Offset.Zero) }
        val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
            zoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
            // Keep the zoomed page's edges from leaving the box.
            val maxX = (zoom - 1f) * widthPx / 2f
            val maxY = (zoom - 1f) * heightPx / 2f
            pan = Offset(
                (pan.x + panChange.x).coerceIn(-maxX, maxX),
                (pan.y + panChange.y).coerceIn(-maxY, maxY),
            )
        }

        bitmap?.let { shown ->
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = "Page $pageNumber of ${navigation.pageCount}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .transformable(transformState)
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
            )
        } ?: Centered { CircularProgressIndicator() }
    }
}
