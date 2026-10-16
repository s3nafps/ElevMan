package com.elevman.ui.review

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import com.elevman.ui.viewer.Attempt
import com.elevman.ui.viewer.attempt
import java.io.File
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// One page at a time, off the main thread: a list of cards would otherwise open a renderer for each
// card at once.
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val thumbnailDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

/**
 * A small picture of page [pageNumber] (1-based) of [file], for a review card. While it is being
 * drawn, and when it cannot be (the file is missing, damaged or password protected, or the page
 * number is out of range), the box shows [NoPreview] instead.
 *
 * Needs a real [PdfRenderer], so it runs on a device only; the review screen takes it as a slot.
 */
@Composable
internal fun PageThumbnail(file: File, pageNumber: Int, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    // Drawn at twice the size it is shown at, so it stays sharp on a dense screen.
    val widthPx = with(density) { ThumbnailWidth.roundToPx() } * 2
    val heightPx = with(density) { ThumbnailHeight.roundToPx() } * 2

    val bitmap: Bitmap? by produceState<Bitmap?>(initialValue = null, file, pageNumber, widthPx, heightPx) {
        value = when (val drawn = attempt { withContext(thumbnailDispatcher) { renderPage(file, pageNumber, widthPx, heightPx) } }) {
            is Attempt.Done -> drawn.value
            Attempt.Failed -> null
        }
    }

    val picture = bitmap
    if (picture == null) {
        NoPreview(modifier)
    } else {
        Image(
            bitmap = picture.asImageBitmap(),
            contentDescription = "Page $pageNumber of the manual",
            contentScale = ContentScale.Fit,
            modifier = modifier.fillMaxSize(),
        )
    }
}

/**
 * Draws page [pageNumber] (1-based) of [file] as large as fits inside [maxWidthPx] by [maxHeightPx],
 * on white. Blocks, so call it off the main thread. Returns null when the page does not exist.
 *
 * The descriptor and the renderer are always closed, whether or not the page was drawn.
 *
 * @throws IOException when the file is missing or is not a readable PDF
 * @throws SecurityException when the PDF is password protected
 */
private fun renderPage(file: File, pageNumber: Int, maxWidthPx: Int, maxHeightPx: Int): Bitmap? {
    var descriptor: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    try {
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(descriptor)
        if (pageNumber !in 1..renderer.pageCount) return null
        return renderer.openPage(pageNumber - 1).use { page ->
            val fit = min(maxWidthPx.toFloat() / page.width, maxHeightPx.toFloat() / page.height)
            val width = (page.width * fit).roundToInt().coerceAtLeast(1)
            val height = (page.height * fit).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // PdfRenderer draws onto what is there, and a transparent page would show black text on black.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (e: Throwable) {
                bitmap.recycle()
                throw e
            }
        }
    } finally {
        try {
            renderer?.close()
        } catch (_: IllegalStateException) {
            // Already closed; nothing left to release.
        } finally {
            try {
                descriptor?.close()
            } catch (_: IOException) {
                // A descriptor that will not close is no reason to crash a thumbnail.
            }
        }
    }
}
