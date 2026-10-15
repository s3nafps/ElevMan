package com.elevman

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class ElevManApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // PdfBox reads its fonts and glyph lists through this loader, so it has to be set up
        // before any PDDocument is loaded.
        PDFBoxResourceLoader.init(this)
    }
}
