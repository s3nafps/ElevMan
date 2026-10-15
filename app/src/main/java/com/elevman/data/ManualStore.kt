package com.elevman.data

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface StoreOutcome {
    /** The PDF was new: it now lives at [file] and has a [ManualEntity] row with id [manualId]. */
    data class Stored(val manualId: String, val file: File) : StoreOutcome

    /** A PDF with identical bytes is already stored; nothing was written. */
    data class Duplicate(val existingManualId: String, val existingTitle: String) : StoreOutcome
}

/**
 * Copies imported PDFs into app-private storage as `manuals/<id>.pdf` and records them.
 *
 * Duplicates are detected by SHA-256 of the bytes only; the title is not compared.
 */
class ManualStore(
    private val filesDir: File,
    private val dao: ManualDao,
    private val clock: Clock,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Streams [source] into a temp file, hashes it, and either returns [StoreOutcome.Duplicate]
     * or moves it to its final name and inserts the row (`pageCount` is 0 until the importer
     * reads the PDF). The caller owns [source] and must close it.
     *
     * Whatever happens, no temp file is left behind, and a file whose row could not be
     * inserted is removed again.
     */
    suspend fun store(source: InputStream, modelId: String, title: String): StoreOutcome =
        withContext(Dispatchers.IO) {
            val dir = File(filesDir, MANUALS_DIR)
            Files.createDirectories(dir.toPath())
            val temp = File.createTempFile("import-", ".tmp", dir)
            var target: File? = null
            var recorded = false
            try {
                val hash = copyAndHash(source, temp)

                val existing = dao.findByHash(hash)
                if (existing != null) {
                    return@withContext StoreOutcome.Duplicate(existing.id, existing.title)
                }

                val id = newId()
                val finalFile = File(dir, "$id.pdf")
                Files.move(temp.toPath(), finalFile.toPath())
                target = finalFile

                // Not cancellable: a cancelled insert can still commit, and the cleanup below
                // would then delete the file of a row that exists.
                withContext(NonCancellable) {
                    dao.upsertAll(
                        listOf(
                            ManualEntity(
                                id = id,
                                modelId = modelId,
                                title = title,
                                fileHash = hash,
                                pageCount = 0,
                                updatedAt = clock.millis(),
                            ),
                        ),
                    )
                    recorded = true
                }
                StoreOutcome.Stored(id, finalFile)
            } finally {
                // No-op once the temp file has been moved; covers duplicate, failure and cancellation.
                temp.delete()
                if (!recorded) target?.delete()
            }
        }

    /** Writes [source] to [dest] and returns the SHA-256 of what was written as lowercase hex. */
    private fun copyAndHash(source: InputStream, dest: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        dest.outputStream().use { out ->
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val MANUALS_DIR = "manuals"
        const val BUFFER_SIZE = 64 * 1024
    }
}
