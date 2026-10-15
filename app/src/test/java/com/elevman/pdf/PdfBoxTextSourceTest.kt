package com.elevman.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfBoxTextSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        // The app does this once in ElevManApp.onCreate. PdfBox must have its resource loader
        // before any PDDocument is created, so the test makes sure of it explicitly rather than
        // relying on Robolectric to run the Application class.
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun extractsPageNumbersAndText() = runTest {
        val pdf = writeTextManual(tempFolder.newFile("text-manual.pdf"))

        val pages = PdfBoxTextSource().pages(pdf)

        assertEquals(listOf(1, 2), pages.map { it.pageNumber })
        assertTrue(pages[0].text.contains("Introduction"))
        assertFalse(pages[0].text.contains("E-21"))
        assertTrue(pages[1].text.contains("E-21 Door interlock fault"))
        assertTrue(pages[1].text.contains("Check the interlock contact"))
        assertTrue(pages[1].text.contains("E-22 Overspeed"))
        assertTrue(hasAnyText(pages))
    }

    @Test
    fun scannedPdfHasNoText() = runTest {
        val pdf = writeScannedManual(tempFolder.newFile("scanned-manual.pdf"))

        val pages = PdfBoxTextSource().pages(pdf)

        assertEquals(1, pages.size)
        assertEquals(1, pages[0].pageNumber)
        assertFalse(hasAnyText(pages))
    }

    /** Two pages drawn with a standard font, so every line is a real text layer. */
    private fun writeTextManual(target: File): File {
        PDDocument().use { document ->
            addTextPage(document, listOf("Introduction"))
            addTextPage(
                document,
                listOf("E-21 Door interlock fault", "Check the interlock contact", "E-22 Overspeed"),
            )
            document.save(target)
        }
        return target
    }

    private fun addTextPage(document: PDDocument, lines: List<String>) {
        val page = PDPage()
        document.addPage(page)
        PDPageContentStream(document, page).use { content ->
            content.beginText()
            content.setFont(PDType1Font.HELVETICA, 14f)
            content.setLeading(18f)
            content.newLineAtOffset(72f, 700f)
            lines.forEach { line ->
                content.showText(line)
                content.newLine()
            }
            content.endText()
        }
    }

    /** One page that only holds an image, the way a scanned or photographed manual does. */
    private fun writeScannedManual(target: File): File {
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            val image = PDImageXObject.createFromByteArray(document, TINY_PNG, "scan.png")
            PDPageContentStream(document, page).use { content ->
                content.drawImage(image, 72f, 72f, 400f, 600f)
            }
            document.save(target)
        }
        return target
    }

    private companion object {
        /** A valid 1x1 PNG, hardcoded so the test does not need java.awt to build one. */
        val TINY_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
            0x54, 0x78, 0xDA.toByte(), 0x63, 0x64, 0x60, 0xF8.toByte(), 0x5F,
            0x0F, 0x00, 0x02, 0x87.toByte(), 0x01, 0x80.toByte(), 0xEB.toByte(), 0x47,
            0xBA.toByte(), 0x92.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45,
            0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}
