package com.elevman.ui.viewer

import com.elevman.pdf.PageText

/**
 * The page numbers of [pages] whose text contains [term], ascending and without repeats.
 *
 * The match ignores case and finds [term] inside a longer word. A blank [term] matches nothing.
 */
fun findPages(pages: List<PageText>, term: String): List<Int> {
    if (term.isBlank()) return emptyList()
    return pages
        .filter { it.text.contains(term, ignoreCase = true) }
        .map { it.pageNumber }
        .distinct()
        .sorted()
}

/**
 * Which page the viewer shows, and which pages match the search term. Plain Kotlin, so it is
 * tested on the JVM; the viewer keeps one and replaces it on every tap.
 *
 * Pages are 1-based. [currentPage] is always inside the document (page 1 if there are no pages),
 * and [matches] are sorted, without repeats, and only pages that exist.
 */
class PageNavigation(
    pageCount: Int,
    currentPage: Int,
    matches: List<Int> = emptyList(),
) {
    val pageCount: Int = pageCount.coerceAtLeast(0)
    val currentPage: Int = currentPage.coerceIn(1, this.pageCount.coerceAtLeast(1))
    val matches: List<Int> = matches.filter { it in 1..this.pageCount }.distinct().sorted()

    val hasPreviousPage: Boolean get() = currentPage > 1
    val hasNextPage: Boolean get() = currentPage < pageCount

    /** The 0-based position of the current page among the matches, or null if it is not a match. */
    val matchIndex: Int?
        get() = matches.indexOf(currentPage).takeIf { it >= 0 }

    /** The same document at [page], held inside the document. */
    fun goTo(page: Int): PageNavigation = PageNavigation(pageCount, page, matches)

    fun nextPage(): PageNavigation = goTo(currentPage + 1)

    fun previousPage(): PageNavigation = goTo(currentPage - 1)

    /** The same page with a new list of matching pages, for when the search term changes. */
    fun withMatches(matches: List<Int>): PageNavigation = PageNavigation(pageCount, currentPage, matches)

    /**
     * The first match after the current page, or the first match if there is none after it
     * (the search wraps). With no matches, nothing moves.
     */
    fun nextMatch(): PageNavigation {
        val target = matches.firstOrNull { it > currentPage } ?: matches.firstOrNull()
        return if (target == null) this else goTo(target)
    }

    /** The last match before the current page, wrapping to the last match. */
    fun previousMatch(): PageNavigation {
        val target = matches.lastOrNull { it < currentPage } ?: matches.lastOrNull()
        return if (target == null) this else goTo(target)
    }
}
