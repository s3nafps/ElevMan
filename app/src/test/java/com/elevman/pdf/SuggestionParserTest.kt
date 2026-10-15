package com.elevman.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionParserTest {

    private fun page(text: String, pageNumber: Int = 1) = PageText(pageNumber, text)

    @Test
    fun parsesCodeWithMeaning() {
        val result = SuggestionParser.errorCodes(listOf(page("E-21 Door interlock fault")))

        assertEquals(listOf(SuggestedCode("E-21", "Door interlock fault", 1)), result)
    }

    @Test
    fun joinsContinuationLines() {
        val result = SuggestionParser.errorCodes(listOf(page("E-22 Overspeed\nCheck governor")))

        assertEquals(1, result.size)
        assertEquals("E-22", result[0].code)
        assertEquals("Overspeed Check governor", result[0].meaning)
    }

    @Test
    fun continuationLinesAreTrimmedBeforeJoining() {
        val result = SuggestionParser.errorCodes(
            listOf(page("  E-22 Overspeed  \r\n   Check governor   \n\t and brake  ")),
        )

        assertEquals("Overspeed Check governor and brake", result.single().meaning)
    }

    @Test
    fun blankLineEndsEntry() {
        val result = SuggestionParser.errorCodes(
            listOf(page("E-21 Door interlock fault\n\nThis paragraph is not part of the entry\nE-22 Overspeed")),
        )

        assertEquals(
            listOf(
                SuggestedCode("E-21", "Door interlock fault", 1),
                SuggestedCode("E-22", "Overspeed", 1),
            ),
            result,
        )
    }

    @Test
    fun whitespaceOnlyLineEndsEntry() {
        val result = SuggestionParser.errorCodes(listOf(page("E-21 Door fault\n   \t \nstray text")))

        assertEquals("Door fault", result.single().meaning)
    }

    @Test
    fun nextCodeLineEndsOpenEntryAndStartsANewOne() {
        val result = SuggestionParser.errorCodes(
            listOf(page("E-21 Door fault\nmore door detail\nE-22 Overspeed\nmore speed detail")),
        )

        assertEquals(
            listOf(
                SuggestedCode("E-21", "Door fault more door detail", 1),
                SuggestedCode("E-22", "Overspeed more speed detail", 1),
            ),
            result,
        )
    }

    @Test
    fun codeKeepsPrintedSpelling() {
        val result = SuggestionParser.errorCodes(listOf(page("E 21 Fault")))

        assertEquals("E 21", result.single().code)
        assertEquals("Fault", result.single().meaning)
    }

    @Test
    fun codeAcceptsTrailingLetterAndNoSeparator() {
        val result = SuggestionParser.errorCodes(listOf(page("ERR104B Brake fault\nF12 Fan fault")))

        assertEquals(listOf("ERR104B", "F12"), result.map { it.code })
    }

    @Test
    fun ignoresLinesThatDoNotStartWithCode() {
        val result = SuggestionParser.errorCodes(listOf(page("12 Hoist motor\ne-21 lower")))

        assertTrue(result.isEmpty())
    }

    @Test
    fun codeWithoutMeaningIsNotAnEntry() {
        val result = SuggestionParser.errorCodes(listOf(page("E-21\nE-22   ")))

        assertTrue(result.isEmpty())
    }

    @Test
    fun entryDoesNotContinueAcrossPages() {
        val result = SuggestionParser.errorCodes(
            listOf(page("E-21 Door fault", 1), page("text at the top of page two", 2)),
        )

        assertEquals(listOf(SuggestedCode("E-21", "Door fault", 1)), result)
    }

    @Test
    fun entriesCarryTheirPageNumber() {
        val result = SuggestionParser.errorCodes(
            listOf(page("E-21 Door fault", 3), page("E-30 Encoder fault", 7)),
        )

        assertEquals(listOf(3, 7), result.map { it.pageNumber })
    }

    @Test
    fun maintenanceKeywordTable() {
        val pages = listOf(
            page("Monthly: inspect door locks\nSemi-annual lubrication\nYearly safety test\nInspect the pit"),
        )

        val result = SuggestionParser.maintenanceTasks(pages)

        assertEquals(
            listOf(
                SuggestedTask("Monthly: inspect door locks", 1, 1),
                SuggestedTask("Semi-annual lubrication", 6, 1),
                SuggestedTask("Yearly safety test", 12, 1),
            ),
            result,
        )
    }

    @Test
    fun semiAnnualMatchesSixNotTwelve() {
        val result = SuggestionParser.maintenanceTasks(listOf(page("Semi-annual rope check")))

        assertEquals(6, result.single().intervalMonths)
    }

    @Test
    fun everyKeywordMapsToItsInterval() {
        val lines = listOf(
            "semi-annual a" to 6,
            "six-monthly b" to 6,
            "every 6 months c" to 6,
            "quarterly d" to 3,
            "monthly e" to 1,
            "annual f" to 12,
            "yearly g" to 12,
            "every 12 months h" to 12,
        )

        val result = SuggestionParser.maintenanceTasks(listOf(page(lines.joinToString("\n") { it.first })))

        assertEquals(lines.map { it.second }, result.map { it.intervalMonths })
    }

    @Test
    fun maintenanceKeywordsMatchCaseInsensitively() {
        val result = SuggestionParser.maintenanceTasks(
            listOf(page("QUARTERLY brake test\nEvery 6 Months: clean the pit")),
        )

        assertEquals(listOf(3, 6), result.map { it.intervalMonths })
    }

    @Test
    fun firstKeywordInTableOrderWinsWhenALineHasSeveral() {
        val result = SuggestionParser.maintenanceTasks(
            listOf(page("Monthly check, annual overhaul\nQuarterly or semi-annual service")),
        )

        assertEquals(listOf(1, 6), result.map { it.intervalMonths })
    }

    @Test
    fun maintenanceNameIsTheTrimmedLineAsPrinted() {
        val result = SuggestionParser.maintenanceTasks(listOf(page("   Yearly Safety TEST \n")))

        assertEquals("Yearly Safety TEST", result.single().name)
    }

    @Test
    fun maintenanceTasksCarryTheirPageNumber() {
        val result = SuggestionParser.maintenanceTasks(
            listOf(page("nothing here", 1), page("Monthly lubrication", 5)),
        )

        assertEquals(listOf(SuggestedTask("Monthly lubrication", 1, 5)), result)
    }

    @Test
    fun emptyInputGivesNothing() {
        assertTrue(SuggestionParser.errorCodes(emptyList()).isEmpty())
        assertTrue(SuggestionParser.maintenanceTasks(emptyList()).isEmpty())
        assertTrue(SuggestionParser.errorCodes(listOf(page(""))).isEmpty())
        assertTrue(SuggestionParser.maintenanceTasks(listOf(page(""))).isEmpty())
    }
}
