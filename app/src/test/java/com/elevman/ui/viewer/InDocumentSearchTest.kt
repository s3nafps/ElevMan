package com.elevman.ui.viewer

import com.elevman.pdf.PageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InDocumentSearchTest {

    private fun page(number: Int, text: String) = PageText(number, text)

    // --- findPages ---------------------------------------------------------------------------

    @Test
    fun findPagesReturnsAscendingMatches() {
        // The term is on pages 2 and 1, and the list is not in page order.
        val pages = listOf(
            page(3, "nothing here"),
            page(2, "E-21 door fault"),
            page(1, "The code E-21 means a door fault"),
        )

        assertEquals(listOf(1, 2), findPages(pages, "E-21"))
    }

    @Test
    fun findPagesEmptyTermGivesEmpty() {
        val pages = listOf(page(1, "E-21 door fault"))

        assertEquals(emptyList<Int>(), findPages(pages, ""))
        assertEquals(emptyList<Int>(), findPages(pages, "   "))
    }

    @Test
    fun findPagesIgnoresCase() {
        val pages = listOf(page(1, "Door Fault"), page(2, "DOOR zone"), page(3, "cabin"))

        assertEquals(listOf(1, 2), findPages(pages, "door"))
        assertEquals(listOf(1, 2), findPages(pages, "DOOR"))
    }

    @Test
    fun findPagesGivesEachPageOnce() {
        // Two entries for page 2, and the term twice on page 1.
        val pages = listOf(page(1, "door door"), page(2, "door"), page(2, "door again"))

        assertEquals(listOf(1, 2), findPages(pages, "door"))
    }

    @Test
    fun findPagesWithNoMatchGivesEmpty() {
        assertEquals(emptyList<Int>(), findPages(listOf(page(1, "cabin")), "door"))
        assertEquals(emptyList<Int>(), findPages(emptyList(), "door"))
    }

    @Test
    fun findPagesMatchesPartOfAWord() {
        // "contains", not whole-word: "ground" is found inside "underground".
        assertEquals(listOf(1), findPages(listOf(page(1, "underground")), "ground"))
    }

    // --- PageNavigation ----------------------------------------------------------------------

    @Test
    fun nextMatchMovesToTheNextMatchingPage() {
        val navigation = PageNavigation(pageCount = 10, currentPage = 3, matches = listOf(2, 5, 8))

        assertEquals(5, navigation.nextMatch().currentPage)
        assertEquals(8, navigation.nextMatch().nextMatch().currentPage)
    }

    @Test
    fun nextMatchSkipsTheCurrentPageEvenWhenItMatches() {
        val navigation = PageNavigation(pageCount = 10, currentPage = 5, matches = listOf(2, 5, 8))

        assertEquals(8, navigation.nextMatch().currentPage)
    }

    @Test
    fun nextMatchWrapsToTheFirstMatch() {
        val navigation = PageNavigation(pageCount = 10, currentPage = 8, matches = listOf(2, 5, 8))

        assertEquals(2, navigation.nextMatch().currentPage)
        // Past the last match, on a page that does not match.
        assertEquals(2, navigation.goTo(9).nextMatch().currentPage)
    }

    @Test
    fun nextMatchWithOneMatchStaysOnIt() {
        val navigation = PageNavigation(pageCount = 10, currentPage = 4, matches = listOf(4))

        assertEquals(4, navigation.nextMatch().currentPage)
    }

    @Test
    fun nextMatchWithNoMatchesStaysPut() {
        val navigation = PageNavigation(pageCount = 10, currentPage = 4)

        assertEquals(4, navigation.nextMatch().currentPage)
        assertEquals(4, navigation.previousMatch().currentPage)
    }

    @Test
    fun previousMatchMovesBackAndWrapsToTheLastMatch() {
        val navigation = PageNavigation(pageCount = 10, currentPage = 6, matches = listOf(2, 5, 8))

        assertEquals(5, navigation.previousMatch().currentPage)
        assertEquals(2, navigation.previousMatch().previousMatch().currentPage)
        assertEquals(8, navigation.previousMatch().previousMatch().previousMatch().currentPage)
    }

    @Test
    fun withMatchesKeepsTheCurrentPage() {
        val navigation = PageNavigation(pageCount = 10, currentPage = 3).withMatches(listOf(7, 9))

        assertEquals(3, navigation.currentPage)
        assertEquals(listOf(7, 9), navigation.matches)
        assertEquals(7, navigation.nextMatch().currentPage)
    }

    @Test
    fun matchesAreSortedDistinctAndInsideTheDocument() {
        val navigation = PageNavigation(pageCount = 5, currentPage = 1, matches = listOf(9, 3, 3, 0, 1))

        assertEquals(listOf(1, 3), navigation.matches)
    }

    @Test
    fun matchIndexSaysWhichMatchThePageIs() {
        val navigation = PageNavigation(pageCount = 10, currentPage = 5, matches = listOf(2, 5, 8))

        assertEquals(1, navigation.matchIndex)
        assertEquals(null, navigation.goTo(6).matchIndex)
    }

    @Test
    fun pageStepsStopAtTheFirstAndLastPage() {
        val first = PageNavigation(pageCount = 3, currentPage = 1)
        val last = PageNavigation(pageCount = 3, currentPage = 3)

        assertFalse(first.hasPreviousPage)
        assertTrue(first.hasNextPage)
        assertEquals(1, first.previousPage().currentPage)
        assertEquals(2, first.nextPage().currentPage)
        assertTrue(last.hasPreviousPage)
        assertFalse(last.hasNextPage)
        assertEquals(3, last.nextPage().currentPage)
        assertEquals(2, last.previousPage().currentPage)
    }

    @Test
    fun aStartPageOutsideTheDocumentIsClamped() {
        assertEquals(1, PageNavigation(pageCount = 4, currentPage = 0).currentPage)
        assertEquals(4, PageNavigation(pageCount = 4, currentPage = 99).currentPage)
        assertEquals(4, PageNavigation(pageCount = 4, currentPage = 1).goTo(99).currentPage)
        assertEquals(1, PageNavigation(pageCount = 4, currentPage = 3).goTo(-5).currentPage)
    }

    @Test
    fun aDocumentWithNoPagesHasNowhereToGo() {
        val navigation = PageNavigation(pageCount = 0, currentPage = 7, matches = listOf(1, 2))

        assertEquals(emptyList<Int>(), navigation.matches)
        assertFalse(navigation.hasPreviousPage)
        assertFalse(navigation.hasNextPage)
        assertEquals(navigation.currentPage, navigation.nextPage().currentPage)
        assertEquals(navigation.currentPage, navigation.nextMatch().currentPage)
    }
}
