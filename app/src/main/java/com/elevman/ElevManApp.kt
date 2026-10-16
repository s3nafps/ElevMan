package com.elevman

import android.app.Application
import android.util.Log
import com.elevman.data.ImportRecovery
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ElevManApp : Application() {

    /** The database and services shared by the whole app. Created in [onCreate]. */
    lateinit var container: AppContainer
        private set

    // Lives as long as the process; only the start-up clean-up runs on it.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // PdfBox reads its fonts and glyph lists through this loader, so it has to be set up
        // before any PDDocument is loaded.
        PDFBoxResourceLoader.init(this)
        container = AppContainer(this)

        // Once per start, off the main thread: put back or clear the files an import that was cut
        // short left behind. A failure is logged; it must not stop the app.
        val manuals = container.db.manualDao()
        appScope.launch {
            try {
                ImportRecovery.recover(filesDir, manuals)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not clean up after an interrupted import", e)
            }
        }
    }

    private companion object {
        const val TAG = "ElevManApp"
    }
}
