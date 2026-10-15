@file:UseSerializers(LocalDateSerializer::class)

package com.elevman.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Review state of an extracted or hand-entered item. Stored and serialized by enum name. */
@Serializable
enum class ReviewStatus { SUGGESTED, CONFIRMED, REJECTED }

// Every entity has a UUID string `id` and an `updatedAt` (epoch millis, UTC) used by the backup
// merge rule. Relations are plain id strings; there are no foreign-key constraints.

@Entity
@Serializable
data class ManufacturerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAt: Long,
)

@Entity
@Serializable
data class ElevatorModelEntity(
    @PrimaryKey val id: String,
    val manufacturerId: String,
    val name: String,
    val notes: String,
    val updatedAt: Long,
)

@Entity
@Serializable
data class ManualEntity(
    @PrimaryKey val id: String,
    val modelId: String,
    val title: String,
    val fileHash: String,
    val pageCount: Int,
    val updatedAt: Long,
)

@Entity
@Serializable
data class ErrorCodeEntity(
    @PrimaryKey val id: String,
    val modelId: String,
    val manualId: String,
    val code: String,
    val normalizedCode: String,
    val meaning: String,
    val causeAndFix: String = "",
    val pageNumber: Int,
    val status: ReviewStatus = ReviewStatus.SUGGESTED,
    val updatedAt: Long,
)

@Entity
@Serializable
data class MaintenanceTaskEntity(
    @PrimaryKey val id: String,
    val modelId: String,
    val manualId: String,
    val name: String,
    val intervalMonths: Int,
    val pageNumber: Int,
    val status: ReviewStatus = ReviewStatus.SUGGESTED,
    val updatedAt: Long,
)

@Entity
@Serializable
data class ElevatorEntity(
    @PrimaryKey val id: String,
    val site: String,
    val customer: String,
    val modelId: String,
    val serialNumber: String,
    val notes: String,
    val updatedAt: Long,
)

@Entity
@Serializable
data class ElevatorTaskEntity(
    @PrimaryKey val id: String,
    val elevatorId: String,
    val maintenanceTaskId: String,
    val startDate: LocalDate,
    val updatedAt: Long,
)

@Entity
@Serializable
data class LogEntryEntity(
    @PrimaryKey val id: String,
    val elevatorId: String,
    val maintenanceTaskId: String?,
    val date: LocalDate,
    val note: String,
    val updatedAt: Long,
)
