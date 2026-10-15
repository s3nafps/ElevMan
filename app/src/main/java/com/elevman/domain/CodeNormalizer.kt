package com.elevman.domain

import java.util.Locale

private val NON_CODE_CHARS = Regex("[^A-Z0-9]")

/**
 * Normalizes an error code for matching: trims, uppercases with [Locale.ROOT], then removes
 * every character that is not `A-Z` or `0-9`. Spelling variants such as "e-21", "E 21" and
 * "E21" therefore all become "E21".
 */
fun normalizeCode(raw: String): String =
    raw.trim().uppercase(Locale.ROOT).replace(NON_CODE_CHARS, "")
