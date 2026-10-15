package com.elevman.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: ElevManDatabase
    private lateinit var filesDir: File
    private lateinit var store: ManualStore

    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
    private val ids = ArrayDeque(listOf("manual-1", "manual-2", "manual-3"))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesDir = tempFolder.newFolder("files")
        store = ManualStore(filesDir, db.manualDao(), clock, newId = { ids.removeFirst() })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun manualsDirFileNames(): List<String> =
        File(filesDir, "manuals").listFiles().orEmpty().map { it.name }.sorted()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun storesNewPdfAndRecordsHash() = runTest {
        val bytes = "%PDF-1.7 first manual".toByteArray()

        val outcome = store.store(ByteArrayInputStream(bytes), "model-1", "Gen2 Service Manual")

        val expectedFile = File(File(filesDir, "manuals"), "manual-1.pdf")
        assertEquals(StoreOutcome.Stored("manual-1", expectedFile), outcome)
        assertTrue(expectedFile.exists())
        assertEquals(bytes.toList(), expectedFile.readBytes().toList())
        assertEquals(
            listOf(
                ManualEntity(
                    id = "manual-1",
                    modelId = "model-1",
                    title = "Gen2 Service Manual",
                    fileHash = sha256Hex(bytes),
                    pageCount = 0,
                    updatedAt = 1_700_000_000_000L,
                ),
            ),
            db.manualDao().getAll(),
        )
        assertEquals(listOf("manual-1.pdf"), manualsDirFileNames())
    }

    @Test
    fun storedHashIsLowercaseHexOfSha256() = runTest {
        // SHA-256 of the empty input is a well-known value.
        store.store(ByteArrayInputStream(ByteArray(0)), "model-1", "Empty")

        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            db.manualDao().getAll().single().fileHash,
        )
    }

    @Test
    fun secondIdenticalPdfIsDuplicate() = runTest {
        val bytes = "%PDF-1.7 same bytes".toByteArray()
        store.store(ByteArrayInputStream(bytes), "model-1", "Original Title")

        val outcome = store.store(ByteArrayInputStream(bytes), "model-2", "Different Title")

        assertEquals(StoreOutcome.Duplicate("manual-1", "Original Title"), outcome)
        assertEquals(listOf("manual-1.pdf"), manualsDirFileNames())
        assertEquals(listOf("manual-1"), db.manualDao().getAll().map { it.id })
    }

    @Test
    fun differentBytesAreNotDuplicate() = runTest {
        val first = store.store(ByteArrayInputStream("%PDF first".toByteArray()), "model-1", "Same Title")
        val second = store.store(ByteArrayInputStream("%PDF second".toByteArray()), "model-1", "Same Title")

        assertTrue(first is StoreOutcome.Stored)
        assertTrue(second is StoreOutcome.Stored)
        assertEquals("manual-2", (second as StoreOutcome.Stored).manualId)
        assertEquals(listOf("manual-1.pdf", "manual-2.pdf"), manualsDirFileNames())
        assertEquals(setOf("manual-1", "manual-2"), db.manualDao().getAll().map { it.id }.toSet())
    }

    @Test
    fun failedReadLeavesNoFileAndNoRow() = runTest {
        val failing = object : InputStream() {
            private var served = 0
            override fun read(): Int {
                if (served >= 4) throw IOException("disk went away")
                served++
                return 'x'.code
            }
        }

        try {
            store.store(failing, "model-1", "Broken")
            fail("expected the read failure to propagate")
        } catch (expected: IOException) {
            assertEquals("disk went away", expected.message)
        }

        assertEquals(emptyList<String>(), manualsDirFileNames())
        assertEquals(emptyList<ManualEntity>(), db.manualDao().getAll())
    }

    @Test
    fun createsManualsDirectoryWhenMissing() = runTest {
        assertFalse(File(filesDir, "manuals").exists())

        store.store(ByteArrayInputStream("%PDF".toByteArray()), "model-1", "Title")

        assertTrue(File(filesDir, "manuals").isDirectory)
    }

    @Test
    fun cancellationDuringInsertStillRecordsTheManualAndKeepsItsFile() = runTest {
        // The caller is cancelled while the row is being inserted. The insert must still finish,
        // otherwise the cleanup would delete the file of a row that may have been committed.
        val real = db.manualDao()
        lateinit var caller: Deferred<StoreOutcome>
        val cancellingDao = object : ManualDao by real {
            override suspend fun upsertAll(rows: List<ManualEntity>) {
                caller.cancel()
                real.upsertAll(rows)
            }
        }
        val cancellableStore = ManualStore(filesDir, cancellingDao, clock, newId = { "manual-1" })

        caller = async(Dispatchers.Default) {
            cancellableStore.store(ByteArrayInputStream("%PDF cancelled".toByteArray()), "model-1", "Title")
        }
        try {
            caller.await()
        } catch (expected: CancellationException) {
            // The caller was cancelled, which is the point of this test.
        }

        assertEquals(listOf("manual-1"), db.manualDao().getAll().map { it.id })
        assertEquals(listOf("manual-1.pdf"), manualsDirFileNames())
    }
}
