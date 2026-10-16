package com.elevman.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The files the backup screen works with. Both functions do blocking disk work, so call them off
 * the main thread.
 */
interface BackupFiles {

    /**
     * Copies the backup the person picked into a new temporary file and returns it. [BackupReader]
     * reads a [File], and the picker hands over a [Uri]. The caller deletes the copy once it has been read.
     *
     * @throws IOException when the picked file cannot be opened or copied. No partial copy is left.
     */
    fun copyPicked(uri: Uri): File

    /**
     * A new export file in the folder the app's `FileProvider` shares. The file does not exist yet.
     * Earlier exports are deleted first: one can be hundreds of MB, and only the newest is ever shared.
     */
    fun newExportFile(): File
}

/** [BackupFiles] under the app's cache directory, where Android may reclaim the space when it runs low. */
class CacheBackupFiles(
    private val resolver: ContentResolver,
    private val cacheDir: File,
    private val clock: Clock,
) : BackupFiles {

    override fun copyPicked(uri: Uri): File {
        val dir = File(cacheDir, PICKED_DIR)
        Files.createDirectories(dir.toPath())
        val target = File(dir, UUID.randomUUID().toString() + ".zip")
        var copied = false
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
            copied = true
            return target
        } finally {
            if (!copied) target.delete()
        }
    }

    override fun newExportFile(): File {
        val dir = File(cacheDir, EXPORTS_DIR)
        dir.listFiles()?.forEach { it.delete() }
        Files.createDirectories(dir.toPath())
        val stamp = LocalDateTime.now(clock).format(STAMP)
        return File(dir, "elevman-backup-$stamp.zip")
    }

    private companion object {
        /** The folder the `FileProvider` shares: `cache-path` `exports/` in `res/xml/file_paths.xml`. */
        const val EXPORTS_DIR = "exports"
        const val PICKED_DIR = "backup-picked"
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
