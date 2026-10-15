package com.elevman.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts page text with PdfBox-Android.
 *
 * `PDFBoxResourceLoader.init` must have run before this is used; the app does that once in
 * `ElevManApp.onCreate`.
 */
class PdfBoxTextSource : PageTextSource {

    /**
     * Returns one [PageText] for every page, in order, including pages with blank text (a
     * scanned page comes back with blank text, it is not left out). Page numbers start at 1.
     */
    override suspend fun pages(file: File): List<PageText> =
        withContext(Dispatchers.IO) {
            PDDocument.load(file).use { document ->
                val stripper = PDFTextStripper()
                (1..document.numberOfPages).map { pageNumber ->
                    stripper.startPage = pageNumber
                    stripper.endPage = pageNumber
                    PageText(pageNumber, stripper.getText(document))
                }
            }
        }
}
