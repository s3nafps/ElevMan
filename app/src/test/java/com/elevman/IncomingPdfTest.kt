package com.elevman

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingPdfTest {

    private val pdf = Uri.parse("content://downloads/manual.pdf")

    @Test
    fun aViewIntentCarriesThePdfInItsData() {
        assertEquals(pdf, pdfFrom(Intent(Intent.ACTION_VIEW).setDataAndType(pdf, "application/pdf")))
    }

    @Test
    fun aSendIntentCarriesThePdfInItsStream() {
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, pdf)

        assertEquals(pdf, pdfFrom(send))
    }

    @Test
    fun otherIntentsCarryNoPdf() {
        assertNull(pdfFrom(Intent(Intent.ACTION_MAIN)))
        assertNull(pdfFrom(Intent(Intent.ACTION_SEND).setType("application/pdf")))
        assertNull(pdfFrom(null))
    }
}
