package com.elevman.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ManufacturerEntity::class,
        ElevatorModelEntity::class,
        ManualEntity::class,
        ErrorCodeEntity::class,
        MaintenanceTaskEntity::class,
        ElevatorEntity::class,
        ElevatorTaskEntity::class,
        LogEntryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ElevManDatabase : RoomDatabase() {
    abstract fun manufacturerDao(): ManufacturerDao
    abstract fun elevatorModelDao(): ElevatorModelDao
    abstract fun manualDao(): ManualDao
    abstract fun errorCodeDao(): ErrorCodeDao
    abstract fun maintenanceTaskDao(): MaintenanceTaskDao
    abstract fun elevatorDao(): ElevatorDao
    abstract fun elevatorTaskDao(): ElevatorTaskDao
    abstract fun logEntryDao(): LogEntryDao
}
