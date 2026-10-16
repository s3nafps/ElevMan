package com.elevman.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * What the app does at start-up about an import that was cut short: a backup import that set a
 * PDF aside as `<id>.pdf.replaced`, and a manual import that left an `import-*.tmp` file.
 */
@RunWith(AndroidJUnit4::class)
class ImportRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: ElevManDatabase
    private lateinit var filesDir: File
    private lateinit var manualsDir: File

    private val oldBytes = "%PDF-1.7 the PDF the person had".toByteArray()
    private val newBytes = "%PDF-1.7 the PDF the backup brought".toByteArray()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesDir = tempFolder.newFolder("files")
        manualsDir = File(filesDir, "manuals").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun hashOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private suspend fun seedManual(id: String, hash: String) {
        db.manualDao().upsertAll(
            listOf(ManualEntity(id = id, modelId = "model-1", title = "Manual $id", fileHash = hash, pageCount = 3, updatedAt = 1_000L)),
        )
    }

    private fun pdf(id: String) = File(manualsDir, "$id.pdf")

    private fun replaced(id: String) = File(manualsDir, "$id.pdf.replaced")

    private suspend fun recover() = ImportRecovery.recover(filesDir, db.manualDao())

    @Test
    fun committedImportDeletesTheReplacedFileAndKeepsTheNewPdf() = runTest {
        // The row was written with the new hash, and the new PDF is in place: the import committed.
        seedManual("m1", hashOf(newBytes))
        pdf("m1").writeBytes(newBytes)
        replaced("m1").writeBytes(oldBytes)

        recover()

        assertFalse(replaced("m1").exists())
        assertArrayEquals(newBytes, pdf("m1").readBytes())
    }

    @Test
    fun interruptedImportPutsTheReplacedFileBack() = runTest {
        // The row still has the old hash, and the file in place is only part of the new PDF.
        seedManual("m1", hashOf(oldBytes))
        pdf("m1").writeBytes(newBytes.copyOf(10))
        replaced("m1").writeBytes(oldBytes)

        recover()

        assertFalse(replaced("m1").exists())
        assertArrayEquals(oldBytes, pdf("m1").readBytes())
    }

    @Test
    fun interruptedImportWithNoPdfInPlacePutsTheReplacedFileBack() = runTest {
        seedManual("m1", hashOf(oldBytes))
        replaced("m1").writeBytes(oldBytes)

        recover()

        assertFalse(replaced("m1").exists())
        assertArrayEquals(oldBytes, pdf("m1").readBytes())
    }

    @Test
    fun replacedFileOfAManualWithNoRowIsPutBack() = runTest {
        // No row at all, so nothing committed: the set-aside file is the person's own PDF.
        pdf("m1").writeBytes(newBytes)
        replaced("m1").writeBytes(oldBytes)

        recover()

        assertFalse(replaced("m1").exists())
        assertArrayEquals(oldBytes, pdf("m1").readBytes())
    }

    @Test
    fun eachManualIsJudgedByItsOwnRow() = runTest {
        seedManual("done", hashOf(newBytes))
        pdf("done").writeBytes(newBytes)
        replaced("done").writeBytes(oldBytes)
        seedManual("cut", hashOf(oldBytes))
        replaced("cut").writeBytes(oldBytes)

        recover()

        assertArrayEquals(newBytes, pdf("done").readBytes())
        assertFalse(replaced("done").exists())
        assertArrayEquals(oldBytes, pdf("cut").readBytes())
        assertFalse(replaced("cut").exists())
    }

    @Test
    fun leftoverImportTempFilesAreDeletedAndNothingElseIs() = runTest {
        File(manualsDir, "import-123.tmp").writeBytes(newBytes)
        File(manualsDir, "import-abc9.tmp").writeBytes(newBytes)
        File(manualsDir, "keep.tmp").writeBytes(newBytes)
        File(manualsDir, "import-1.pdf").writeBytes(newBytes)
        seedManual("m1", hashOf(newBytes))
        pdf("m1").writeBytes(newBytes)

        recover()

        assertFalse(File(manualsDir, "import-123.tmp").exists())
        assertFalse(File(manualsDir, "import-abc9.tmp").exists())
        assertTrue(File(manualsDir, "keep.tmp").exists())
        assertTrue(File(manualsDir, "import-1.pdf").exists())
        assertArrayEquals(newBytes, pdf("m1").readBytes())
    }

    @Test
    fun aPhoneWithNoManualsFolderIsLeftAlone() = runTest {
        manualsDir.delete()

        recover()

        assertFalse(manualsDir.exists())
        assertEquals(emptyList<ManualEntity>(), db.manualDao().getAll())
    }
}
