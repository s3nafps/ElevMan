package com.elevman.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * One PDF opened with [PdfRenderer], drawn a page at a time.
 *
 * [PdfRenderer] is not thread safe and a page is slow to render, so every call to it, including
 * the opening and the closing, runs one after another on a single worker thread of its own. That
 * keeps the main thread free, and means [close] can never pull the renderer from under a page
 * that is still being drawn: the close is queued behind it.
 *
 * Nothing is opened until [open] is called, so creating one costs nothing. Call [close] exactly
 * once when done (extra calls do nothing). It closes the renderer and the file descriptor, even
 * when [open] failed halfway or has not finished.
 */
internal class PdfPageRenderer(private val file: File) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "pdf-renderer").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val closed = AtomicBoolean(false)

    // Only touched on the worker thread.
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    /**
     * Opens the file and returns its page count.
     *
     * @throws java.io.FileNotFoundException if the file is not there
     * @throws IOException if it is not a readable PDF
     * @throws SecurityException if it is password protected
     */
    suspend fun open(): Int = withContext(dispatcher) {
        throwIfClosed()
        // A second open would drop the first descriptor and renderer without closing them.
        check(renderer == null) { "open() was already called" }
        val opened = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdf = try {
            PdfRenderer(opened)
        } catch (e: Throwable) {
            // The renderer never took the descriptor, so nobody else will close it.
            opened.close()
            throw e
        }
        descriptor = opened
        renderer = pdf
        pdf.pageCount
    }

    /**
     * Draws page [pageNumber] (1-based) as large as fits inside [maxWidthPx] by [maxHeightPx],
     * on white. The bitmap is cut down if it would be very large, so a big screen cannot run
     * the phone out of memory.
     */
    suspend fun render(pageNumber: Int, maxWidthPx: Int, maxHeightPx: Int): Bitmap =
        withContext(dispatcher) {
            throwIfClosed()
            val pdf = checkNotNull(renderer) { "render() before open()" }
            pdf.openPage(pageNumber - 1).use { page ->
                val fit = min(maxWidthPx.toFloat() / page.width, maxHeightPx.toFloat() / page.height)
                var width = (page.width * fit).roundToInt().coerceAtLeast(1)
                var height = (page.height * fit).roundToInt().coerceAtLeast(1)
                val pixels = width.toLong() * height
                if (pixels > MAX_PIXELS) {
                    val shrink = sqrt(MAX_PIXELS.toDouble() / pixels)
                    width = (width * shrink).roundToInt().coerceAtLeast(1)
                    height = (height * shrink).roundToInt().coerceAtLeast(1)
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    // PdfRenderer draws onto what is there; a transparent page would show black
                    // text on the dark theme's black.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } catch (e: Throwable) {
                    bitmap.recycle()
                    throw e
                }
            }
        }

    /** Closes the renderer and the descriptor on the worker thread, after the work queued before. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.execute { closeNow() }
        // Lets the queued close run, then ends the thread.
        executor.shutdown()
    }

    private fun closeNow() {
        val pdf = renderer
        val opened = descriptor
        renderer = null
        descriptor = null
        try {
            pdf?.close()
        } catch (_: IllegalStateException) {
            // Already closed; nothing left to release.
        } finally {
            try {
                opened?.close()
            } catch (_: IOException) {
                // Nothing to do about a descriptor that will not close, and this thread is no place to crash.
            }
        }
    }

    // A call that was queued before close() but runs after it: stop quietly, the screen is gone.
    private fun throwIfClosed() {
        if (closed.get()) throw CancellationException("PDF closed")
    }

    private companion object {
        // About 32 MB as ARGB_8888.
        const val MAX_PIXELS = 8_000_000L
    }
}
