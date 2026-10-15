package com.elevman.backup

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.serialization.SerializationException

sealed interface ReadResult {
    /**
     * The backup passed every check. Its manual PDFs are extracted under [stagingDir], each at the
     * path [backupManualEntryName] gives for its id.
     */
    data class Valid(val document: BackupDocument, val stagingDir: File) : ReadResult

    /** The backup was rejected and nothing was left on disk. [reason] is fit to show to the user. */
    data class Invalid(val reason: String) : ReadResult
}

/**
 * Validates a backup zip written by [BackupWriter] and stages its PDFs for import.
 *
 * The whole zip is checked through its entry list before anything is written, so a rejected
 * backup leaves [stagingRoot] exactly as it was. Only a backup that passes every check gets a new
 * directory under [stagingRoot], named by a fresh UUID. The caller owns that directory and
 * deletes it once the import has finished or been cancelled.
 */
class BackupReader(private val stagingRoot: File) {

    /**
     * Checks [zip] and, if it is a good backup, extracts the PDFs it references.
     *
     * The checks run in this order and the first failure wins: valid zip, safe entry names,
     * readable JSON entry ([BACKUP_JSON_ENTRY]), supported version, every manual's PDF present.
     *
     * Throws [java.io.IOException] if the zip cannot be read or the staging directory cannot be
     * written. Staging is removed again before the exception propagates.
     */
    fun read(zip: File): ReadResult {
        val zipFile = try {
            ZipFile(zip)
        } catch (_: ZipException) {
            return ReadResult.Invalid(NOT_A_BACKUP)
        }
        return zipFile.use { validateAndStage(it) }
    }

    private fun validateAndStage(zip: ZipFile): ReadResult {
        if (zip.entries().asSequence().any { isUnsafe(it.name) }) {
            return ReadResult.Invalid(UNSAFE_PATH)
        }

        val document = readDocument(zip) ?: return ReadResult.Invalid(DAMAGED)
        if (document.version != BACKUP_VERSION) return ReadResult.Invalid(UNSUPPORTED_VERSION)

        val missing = document.manuals.firstOrNull { pdfEntry(zip, it.id) == null }
        if (missing != null) return ReadResult.Invalid("The PDF for ${missing.title} is missing from the backup.")

        return stage(zip, document)
    }

    /** Null when the JSON entry is absent, unreadable or not a [BackupDocument]. */
    private fun readDocument(zip: ZipFile): BackupDocument? {
        val entry = zip.getEntry(BACKUP_JSON_ENTRY)?.takeUnless { it.isDirectory } ?: return null
        return try {
            val text = zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
            BackupJson.decodeFromString(BackupDocument.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ZipException) {
            null
        }
    }

    /** The PDF entry of manual [id], or null if there is none (a directory of that name does not count). */
    private fun pdfEntry(zip: ZipFile, id: String): ZipEntry? =
        zip.getEntry(backupManualEntryName(id))?.takeUnless { it.isDirectory }

    /**
     * Extracts the PDFs of [document] into a new directory under [stagingRoot].
     *
     * Every entry name was vetted by [isUnsafe] and each target is built from
     * [backupManualEntryName], so no target can lie outside the staging directory.
     */
    private fun stage(zip: ZipFile, document: BackupDocument): ReadResult {
        val stagingDir = File(stagingRoot, UUID.randomUUID().toString())
        var done = false
        try {
            Files.createDirectories(stagingDir.toPath())
            for (manual in document.manuals.distinctBy { it.id }) {
                val entry = checkNotNull(pdfEntry(zip, manual.id)) { "PDF entry vanished for ${manual.id}" }
                val target = File(stagingDir, backupManualEntryName(manual.id))
                Files.createDirectories(target.toPath().parent)
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
            done = true
            return ReadResult.Valid(document, stagingDir)
        } catch (_: ZipException) {
            // The archive's own data is bad (for example a corrupt compressed stream).
            return ReadResult.Invalid(DAMAGED)
        } finally {
            if (!done) stagingDir.deleteRecursively()
        }
    }

    /** True for an entry name that is absolute, has a drive prefix, climbs with `..` or uses a backslash. */
    private fun isUnsafe(name: String): Boolean =
        name.startsWith('/') ||
            '\\' in name ||
            ".." in name ||
            (name.length >= 2 && name[1] == ':' && name[0].isLetter())

    private companion object {
        const val NOT_A_BACKUP = "This is not a backup file."
        const val UNSAFE_PATH = "The backup contains an unsafe file path."
        const val DAMAGED = "The backup file is damaged."
        const val UNSUPPORTED_VERSION = "Unsupported backup version"
    }
}
