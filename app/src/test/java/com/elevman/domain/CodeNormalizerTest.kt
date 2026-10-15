package com.elevman.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeNormalizerTest {

    @Test
    fun normalizesSpellingVariants() {
        assertEquals("E21", normalizeCode("e-21"))
        assertEquals("E21", normalizeCode("E 21"))
        assertEquals("E21", normalizeCode("E21"))
        // U+2013 en dash
        assertEquals("E21", normalizeCode(" e\u201321 "))
    }

    @Test
    fun normalizesEmptyToEmpty() {
        assertEquals("", normalizeCode(""))
    }
}
