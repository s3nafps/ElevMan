package com.elevman

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class ElevManApp : Application() {

    /** The database and services shared by the whole app. Created in [onCreate]. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // PdfBox reads its fonts and glyph lists through this loader, so it has to be set up
        // before any PDDocument is loaded.
        PDFBoxResourceLoader.init(this)
        container = AppContainer(this)
    }
}
