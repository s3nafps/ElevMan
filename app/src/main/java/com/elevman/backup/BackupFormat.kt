package com.elevman.backup

import com.elevman.data.ElevatorEntity
import com.elevman.data.ElevatorModelEntity
import com.elevman.data.ElevatorTaskEntity
import com.elevman.data.ErrorCodeEntity
import com.elevman.data.LogEntryEntity
import com.elevman.data.MaintenanceTaskEntity
import com.elevman.data.ManualEntity
import com.elevman.data.ManufacturerEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Version written to, and expected from, `backup.json`. */
const val BACKUP_VERSION = 1

/** Name of the JSON entry in the backup zip. It is always the first entry. */
const val BACKUP_JSON_ENTRY = "backup.json"

/** Zip path of the PDF belonging to the manual with the given [manualId]. */
fun backupManualEntryName(manualId: String): String = "manuals/$manualId.pdf"

/**
 * The one [Json] used to write and read backups.
 *
 * `encodeDefaults` is required: properties such as `ErrorCodeEntity.status` (default `SUGGESTED`)
 * and `causeAndFix` (default `""`) equal their declared defaults, and the default Json would drop
 * them from the file. A backup must carry every field explicitly.
 */
val BackupJson = Json { encodeDefaults = true }

/** The content of `backup.json`: every table, in full. */
@Serializable
data class BackupDocument(
    val version: Int,
    val exportedAt: Long,
    val manufacturers: List<ManufacturerEntity>,
    val models: List<ElevatorModelEntity>,
    val manuals: List<ManualEntity>,
    val errorCodes: List<ErrorCodeEntity>,
    val maintenanceTasks: List<MaintenanceTaskEntity>,
    val elevators: List<ElevatorEntity>,
    val elevatorTasks: List<ElevatorTaskEntity>,
    val logEntries: List<LogEntryEntity>,
)

/** This document as the UTF-8 bytes that go into the `backup.json` entry. */
fun BackupDocument.toJsonBytes(): ByteArray =
    BackupJson.encodeToString(BackupDocument.serializer(), this).toByteArray(Charsets.UTF_8)
