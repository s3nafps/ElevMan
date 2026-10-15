package com.elevman.backup

import com.elevman.data.ManualEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Plain JVM tests: the reader needs neither Room nor Android. */
class BackupReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var stagingRoot: File
    private lateinit var reader: BackupReader

    private val pdfOne = "%PDF-1.7 manual one — ünïcode".toByteArray()
    private val pdfTwo = ByteArray(5_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        stagingRoot = tempFolder.newFolder("staging")
        reader = BackupReader(stagingRoot)
    }

    private fun manual(id: String, title: String) = ManualEntity(
        id = id,
        modelId = "model-1",
        title = title,
        fileHash = "hash-$id",
        pageCount = 3,
        updatedAt = 10L,
    )

    private fun document(manuals: List<ManualEntity>, version: Int = BACKUP_VERSION) = BackupDocument(
        version = version,
        exportedAt = 1_800_000_000_000L,
        manufacturers = emptyList(),
        models = emptyList(),
        manuals = manuals,
        errorCodes = emptyList(),
        maintenanceTasks = emptyList(),
        elevators = emptyList(),
        elevatorTasks = emptyList(),
        logEntries = emptyList(),
    )

    /** Writes a zip with exactly these entries, in this order, and returns the file. */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val file = tempFolder.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun backupZip(
        document: BackupDocument,
        pdfs: Map<String, ByteArray>,
        vararg extra: Pair<String, ByteArray>,
    ): File {
        val entries = mutableListOf<Pair<String, ByteArray>>(BACKUP_JSON_ENTRY to document.toJsonBytes())
        pdfs.forEach { (id, bytes) -> entries += backupManualEntryName(id) to bytes }
        entries += extra
        return zipOf(*entries.toTypedArray())
    }

    private fun stagingRootIsEmpty(): Boolean = stagingRoot.list()!!.isEmpty()

    private fun assertInvalid(expected: String, result: ReadResult) {
        assertEquals(ReadResult.Invalid(expected), result)
    }

    @Test
    fun validBackupExtractsPdfs() {
        val doc = document(listOf(manual("m1", "Otis Gen2 manual"), manual("m2", "Kone MonoSpace manual")))
        val zip = backupZip(doc, mapOf("m1" to pdfOne, "m2" to pdfTwo))

        val result = reader.read(zip)

        result as ReadResult.Valid
        assertEquals(doc, result.document)
        assertEquals(stagingRoot, result.stagingDir.parentFile)
        assertArrayEquals(pdfOne, File(result.stagingDir, backupManualEntryName("m1")).readBytes())
        assertArrayEquals(pdfTwo, File(result.stagingDir, backupManualEntryName("m2")).readBytes())
    }

    @Test
    fun validBackupWithoutManualsIsValid() {
        val doc = document(emptyList())

        val result = reader.read(backupZip(doc, emptyMap()))

        result as ReadResult.Valid
        assertEquals(doc, result.document)
        assertTrue(result.stagingDir.isDirectory)
        assertEquals(stagingRoot, result.stagingDir.parentFile)
    }

    @Test
    fun eachReadGetsItsOwnStagingDirectory() {
        val zip = backupZip(document(listOf(manual("m1", "One"))), mapOf("m1" to pdfOne))

        val first = reader.read(zip) as ReadResult.Valid
        val second = reader.read(zip) as ReadResult.Valid

        assertFalse(first.stagingDir == second.stagingDir)
        assertTrue(File(first.stagingDir, backupManualEntryName("m1")).isFile)
        assertTrue(File(second.stagingDir, backupManualEntryName("m1")).isFile)
    }

    @Test
    fun onlyReferencedPdfsAreExtracted() {
        val doc = document(listOf(manual("m1", "One")))
        val zip = backupZip(doc, mapOf("m1" to pdfOne), backupManualEntryName("orphan") to pdfTwo)

        val result = reader.read(zip) as ReadResult.Valid

        assertTrue(File(result.stagingDir, backupManualEntryName("m1")).isFile)
        assertFalse(File(result.stagingDir, backupManualEntryName("orphan")).exists())
    }

    @Test
    fun corruptFileIsInvalid() {
        val file = tempFolder.newFile()
        file.writeBytes(ByteArray(2_000).also { Random(7).nextBytes(it) })

        assertInvalid("This is not a backup file.", reader.read(file))
        assertTrue(stagingRootIsEmpty())
    }

    @Test
    fun emptyFileIsInvalid() {
        val file = tempFolder.newFile()

        assertInvalid("This is not a backup file.", reader.read(file))
        assertTrue(stagingRootIsEmpty())
    }

    @Test
    fun wrongVersionIsInvalid() {
        val doc = document(listOf(manual("m1", "One")), version = BACKUP_VERSION + 1)
        val zip = backupZip(doc, mapOf("m1" to pdfOne))

        assertInvalid("Unsupported backup version", reader.read(zip))
        assertTrue(stagingRootIsEmpty())
    }

    @Test
    fun missingBackupJsonIsDamaged() {
        val zip = zipOf(backupManualEntryName("m1") to pdfOne)

        assertInvalid("The backup file is damaged.", reader.read(zip))
        assertTrue(stagingRootIsEmpty())
    }

    @Test
    fun undecodableBackupJsonIsDamaged() {
        val zip = zipOf(BACKUP_JSON_ENTRY to "{ this is not json".toByteArray())

        assertInvalid("The backup file is damaged.", reader.read(zip))
        assertTrue(stagingRootIsEmpty())
    }

    @Test
    fun backupJsonOfWrongShapeIsDamaged() {
        val zip = zipOf(BACKUP_JSON_ENTRY to """{"version":1}""".toByteArray())

        assertInvalid("The backup file is damaged.", reader.read(zip))
        assertTrue(stagingRootIsEmpty())
    }

    @Test
    fun missingPdfIsInvalidAndNothingExtracted() {
        val doc = document(listOf(manual("m1", "Otis Gen2 manual"), manual("m2", "Kone MonoSpace manual")))
        // m1's PDF is present and would be extracted if the reader did not check first.
        val zip = backupZip(doc, mapOf("m1" to pdfOne))

        assertInvalid("The PDF for Kone MonoSpace manual is missing from the backup.", reader.read(zip))
        assertTrue(stagingRootIsEmpty())
    }

    @Test
    fun zipSlipEntryIsInvalid() {
        val doc = document(listOf(manual("m1", "One")))
        val zip = backupZip(doc, mapOf("m1" to pdfOne), "../evil.pdf" to pdfTwo)

        assertInvalid("The backup contains an unsafe file path.", reader.read(zip))
        assertTrue(stagingRootIsEmpty())
        assertFalse(File(stagingRoot.parentFile, "evil.pdf").exists())
    }

    @Test
    fun nestedParentSegmentIsInvalid() {
        val doc = document(listOf(manual("m1", "One")))
        val zip = backupZip(doc, mapOf("m1" to pdfOne), "manuals/../../evil.pdf" to pdfTwo)

        assertInvalid("The backup contains an unsafe file path.", reader.read(zip))
        assertTrue(stagingRootIsEmpty())
    }

    @Test
    fun backslashEntryIsInvalid() {
        val doc = document(listOf(manual("m1", "One")))
        val zip = backupZip(doc, mapOf("m1" to pdfOne), "manuals\\evil.pdf" to pdfTwo)

        assertInvalid("The backup contains an unsafe file path.", reader.read(zip))
        assertTrue(stagingRootIsEmpty())
    }

    @Test
    fun absolutePathEntryIsInvalid() {
        val doc = document(listOf(manual("m1", "One")))
        val zip = backupZip(doc, mapOf("m1" to pdfOne), "/tmp/evil.pdf" to pdfTwo)

        assertInvalid("The backup contains an unsafe file path.", reader.read(zip))
        assertTrue(stagingRootIsEmpty())
    }

    @Test
    fun windowsDriveEntryIsInvalid() {
        val doc = document(listOf(manual("m1", "One")))
        val zip = backupZip(doc, mapOf("m1" to pdfOne), "C:/evil.pdf" to pdfTwo)

        assertInvalid("The backup contains an unsafe file path.", reader.read(zip))
        assertTrue(stagingRootIsEmpty())
    }

    @Test
    fun unsafePathIsReportedBeforeAnythingInsideBackupJson() {
        // Wrong version and a missing PDF, but the unsafe path is checked first.
        val doc = document(listOf(manual("m1", "One")), version = BACKUP_VERSION + 1)
        val zip = backupZip(doc, emptyMap(), "../evil.pdf" to pdfTwo)

        assertInvalid("The backup contains an unsafe file path.", reader.read(zip))
        assertTrue(stagingRootIsEmpty())
    }

    @Test
    fun wrongVersionIsReportedBeforeMissingPdf() {
        val doc = document(listOf(manual("m1", "One")), version = BACKUP_VERSION + 1)
        val zip = backupZip(doc, emptyMap())

        assertInvalid("Unsupported backup version", reader.read(zip))
        assertTrue(stagingRootIsEmpty())
    }

    @Test
    fun damagedPdfDataIsInvalidAndStagingIsCleanedUp() {
        val doc = document(listOf(manual("m1", "One"), manual("m2", "Two")))
        val zip = backupZip(doc, mapOf("m1" to pdfOne, "m2" to pdfTwo))
        corruptDeflateStream(zip, backupManualEntryName("m2"))

        assertInvalid("The backup file is damaged.", reader.read(zip))
        // m1 was extracted before m2 failed; the whole staging directory must be gone again.
        assertTrue(stagingRootIsEmpty())
    }

    /** Overwrites the first byte of the entry's deflate data with a reserved block type. */
    private fun corruptDeflateStream(zip: File, entryName: String) {
        val bytes = zip.readBytes()
        val name = entryName.toByteArray(Charsets.UTF_8)
        val nameAt = indexOf(bytes, name)
        if (nameAt < 0) fail("entry name not found in zip")
        val headerAt = nameAt - LOCAL_HEADER_SIZE
        val extraLength = (bytes[headerAt + 28].toInt() and 0xff) or ((bytes[headerAt + 29].toInt() and 0xff) shl 8)
        bytes[nameAt + name.size + extraLength] = 0x07
        zip.writeBytes(bytes)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        const val LOCAL_HEADER_SIZE = 30
    }
}
