package com.elevman.pdf

import java.io.File

/** The text of one PDF page. [pageNumber] is 1-based, like the page numbers a reader sees. */
data class PageText(val pageNumber: Int, val text: String)

/** Reads the text layer of a PDF, one [PageText] per page. */
interface PageTextSource {
    suspend fun pages(file: File): List<PageText>
}

/** False for a scanned PDF: pages exist, but none of them has a text layer. */
fun hasAnyText(pages: List<PageText>): Boolean = pages.any { it.text.isNotBlank() }
