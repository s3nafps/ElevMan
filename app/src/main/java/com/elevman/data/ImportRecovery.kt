package com.elevman.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cleans up after an import that was cut short, for example by the phone running out of battery.
 * It runs once, when the app starts.
 *
 * Two kinds of file can be left in `manuals/`:
 *
 * - `<id>.pdf.replaced`: a backup import set the manual's old PDF aside before copying the new one
 *   over it. If the database now holds the hash of the PDF in place, the import committed and the
 *   old file is garbage. Otherwise the import did not finish, and the old file is the person's
 *   own PDF: it goes back where it was.
 * - `import-*.tmp`: a manual that was being copied in. It never became a manual, so it is deleted.
 */
object ImportRecovery {

    private const val REPLACED_SUFFIX = ".pdf.replaced"

    /**
     * Recovers every file described above under `filesDir/manuals`, on [Dispatchers.IO]. A file
     * that cannot be handled does not stop the others; the first failure is thrown at the end,
     * with the rest attached to it as suppressed.
     */
    suspend fun recover(filesDir: File, manuals: ManualDao) {
        withContext(Dispatchers.IO) {
            val dir = manualsDir(filesDir)
            val files = dir.listFiles() ?: return@withContext
            var failure: Exception? = null
            fun remember(e: Exception) {
                val first = failure
                if (first == null) failure = e else first.addSuppressed(e)
            }

            // The temp files first: they need no database, so a failing query cannot leave them behind.
            val (temps, rest) = files.partition { it.name.startsWith("import-") && it.name.endsWith(".tmp") }
            for (file in temps) {
                try {
                    Files.deleteIfExists(file.toPath())
                } catch (e: IOException) {
                    remember(e)
                }
            }
            for (file in rest.filter { it.name.endsWith(REPLACED_SUFFIX) }) {
                try {
                    settleReplaced(dir, file, manuals)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A file or a query that fails must not leave the other manuals unrecovered.
                    remember(e)
                }
            }
            failure?.let { throw it }
        }
    }

    private suspend fun settleReplaced(dir: File, replaced: File, manuals: ManualDao) {
        val id = replaced.name.removeSuffix(REPLACED_SUFFIX)
        val pdf = File(dir, "$id.pdf")
        val row = if (id.isEmpty()) null else manuals.findById(id)
        val committed = row != null && pdf.isFile && sha256Hex(pdf) == row.fileHash
        if (committed) {
            Files.deleteIfExists(replaced.toPath())
        } else {
            Files.move(replaced.toPath(), pdf.toPath(), REPLACE_EXISTING)
        }
    }

    /** The SHA-256 of [file]'s bytes as lowercase hex, the form [ManualEntity.fileHash] is stored in. */
    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
