package com.elevman.pdf

/** An error code found in manual text, offered to a person to review. */
data class SuggestedCode(val code: String, val meaning: String, val pageNumber: Int)

/** A maintenance task found in manual text, offered to a person to review. */
data class SuggestedTask(val name: String, val intervalMonths: Int, val pageNumber: Int)

/**
 * Turns page text into suggestions. Nothing here is saved: a person reviews every suggestion
 * before it becomes an error code or a maintenance task.
 */
object SuggestionParser {
    private val errorCodeLine = Regex("""^([A-Z]{1,4}[- ]?[0-9]{1,4}[A-Z]?)\s+(\S.*)$""")

    /**
     * Checked in order, and the first keyword found in the lowercased line wins.
     * `semi-annual` must stay ahead of `annual`, which it contains.
     */
    private val intervalKeywords: List<Pair<String, Int>> = listOf(
        "semi-annual" to 6,
        "six-monthly" to 6,
        "every 6 months" to 6,
        "quarterly" to 3,
        "monthly" to 1,
        "annual" to 12,
        "yearly" to 12,
        "every 12 months" to 12,
    )

    /**
     * A line that matches the error-code pattern starts an entry. Following non-blank lines
     * continue it, joined to the meaning with a single space. A blank line, the next code line,
     * or the end of the page closes it.
     */
    fun errorCodes(pages: List<PageText>): List<SuggestedCode> {
        val found = mutableListOf<SuggestedCode>()
        for (page in pages) {
            var openCode: String? = null
            var openMeaning = ""

            fun closeEntry() {
                openCode?.let { found += SuggestedCode(it, openMeaning, page.pageNumber) }
                openCode = null
                openMeaning = ""
            }

            for (rawLine in page.text.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty()) {
                    closeEntry()
                    continue
                }
                val start = errorCodeLine.matchEntire(line)
                if (start != null) {
                    closeEntry()
                    openCode = start.groupValues[1]
                    openMeaning = start.groupValues[2]
                } else if (openCode != null) {
                    openMeaning = "$openMeaning $line"
                }
            }
            closeEntry()
        }
        return found
    }

    /** A line that contains an interval keyword, in any case, is a task named by the trimmed line. */
    fun maintenanceTasks(pages: List<PageText>): List<SuggestedTask> {
        val found = mutableListOf<SuggestedTask>()
        for (page in pages) {
            for (rawLine in page.text.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val lower = line.lowercase()
                val months = intervalKeywords.firstOrNull { (keyword, _) -> lower.contains(keyword) }?.second
                if (months != null) found += SuggestedTask(line, months, page.pageNumber)
            }
        }
        return found
    }
}
