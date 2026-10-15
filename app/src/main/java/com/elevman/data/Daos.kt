package com.elevman.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

// Each DAO starts with the two operations every table needs. Later tasks add their own queries.

@Dao
interface ManufacturerDao {
    @Upsert
    suspend fun upsertAll(rows: List<ManufacturerEntity>)

    @Query("SELECT * FROM ManufacturerEntity")
    suspend fun getAll(): List<ManufacturerEntity>
}

@Dao
interface ElevatorModelDao {
    @Upsert
    suspend fun upsertAll(rows: List<ElevatorModelEntity>)

    @Query("SELECT * FROM ElevatorModelEntity")
    suspend fun getAll(): List<ElevatorModelEntity>
}

@Dao
interface ManualDao {
    @Upsert
    suspend fun upsertAll(rows: List<ManualEntity>)

    @Query("SELECT * FROM ManualEntity")
    suspend fun getAll(): List<ManualEntity>

    @Query("SELECT * FROM ManualEntity WHERE fileHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): ManualEntity?

    @Query("SELECT * FROM ManualEntity WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ManualEntity?
}

@Dao
interface ErrorCodeDao {
    @Upsert
    suspend fun upsertAll(rows: List<ErrorCodeEntity>)

    @Query("SELECT * FROM ErrorCodeEntity")
    suspend fun getAll(): List<ErrorCodeEntity>
}

@Dao
interface MaintenanceTaskDao {
    @Upsert
    suspend fun upsertAll(rows: List<MaintenanceTaskEntity>)

    @Query("SELECT * FROM MaintenanceTaskEntity")
    suspend fun getAll(): List<MaintenanceTaskEntity>
}

@Dao
interface ElevatorDao {
    @Upsert
    suspend fun upsertAll(rows: List<ElevatorEntity>)

    @Query("SELECT * FROM ElevatorEntity")
    suspend fun getAll(): List<ElevatorEntity>
}

@Dao
interface ElevatorTaskDao {
    @Upsert
    suspend fun upsertAll(rows: List<ElevatorTaskEntity>)

    @Query("SELECT * FROM ElevatorTaskEntity")
    suspend fun getAll(): List<ElevatorTaskEntity>
}

@Dao
interface LogEntryDao {
    @Upsert
    suspend fun upsertAll(rows: List<LogEntryEntity>)

    @Query("SELECT * FROM LogEntryEntity")
    suspend fun getAll(): List<LogEntryEntity>
}
