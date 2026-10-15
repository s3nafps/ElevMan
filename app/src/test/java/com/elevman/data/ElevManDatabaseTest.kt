package com.elevman.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElevManDatabaseTest {

    private lateinit var db: ElevManDatabase

    @Before
    fun openDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ElevManDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun upsertAndReadBack() = runTest {
        val manufacturer = ManufacturerEntity(id = "man-1", name = "Otis", updatedAt = 1_000L)
        val model = ElevatorModelEntity(
            id = "model-1",
            manufacturerId = "man-1",
            name = "Gen2",
            notes = "Machine-room-less",
            updatedAt = 2_000L,
        )
        val manual = ManualEntity(
            id = "manual-1",
            modelId = "model-1",
            title = "Gen2 Service Manual",
            fileHash = "abc123",
            pageCount = 120,
            updatedAt = 3_000L,
        )
        val errorCode = ErrorCodeEntity(
            id = "code-1",
            modelId = "model-1",
            manualId = "manual-1",
            code = "E-05",
            normalizedCode = "E05",
            meaning = "Door zone sensor fault",
            causeAndFix = "Check the sensor wiring.",
            pageNumber = 42,
            status = ReviewStatus.CONFIRMED,
            updatedAt = 4_000L,
        )

        db.manufacturerDao().upsertAll(listOf(manufacturer))
        db.elevatorModelDao().upsertAll(listOf(model))
        db.manualDao().upsertAll(listOf(manual))
        db.errorCodeDao().upsertAll(listOf(errorCode))

        assertEquals(listOf(manufacturer), db.manufacturerDao().getAll())
        assertEquals(listOf(model), db.elevatorModelDao().getAll())
        assertEquals(listOf(manual), db.manualDao().getAll())
        assertEquals(listOf(errorCode), db.errorCodeDao().getAll())
    }

    @Test
    fun errorCodeDefaultsToSuggested() {
        val errorCode = ErrorCodeEntity(
            id = "code-1",
            modelId = "model-1",
            manualId = "manual-1",
            code = "E-05",
            normalizedCode = "E05",
            meaning = "Door zone sensor fault",
            pageNumber = 42,
            updatedAt = 4_000L,
        )

        assertEquals(ReviewStatus.SUGGESTED, errorCode.status)
        assertEquals("", errorCode.causeAndFix)
    }

    @Test
    fun datesAndNullableLinksRoundTrip() = runTest {
        val task = ElevatorTaskEntity(
            id = "et-1",
            elevatorId = "elev-1",
            maintenanceTaskId = "task-1",
            startDate = LocalDate.of(2026, 2, 28),
            updatedAt = 5_000L,
        )
        val entries = listOf(
            LogEntryEntity(
                id = "log-1",
                elevatorId = "elev-1",
                maintenanceTaskId = "task-1",
                date = LocalDate.of(2026, 10, 15),
                note = "Greased the guide rails.",
                updatedAt = 6_000L,
            ),
            LogEntryEntity(
                id = "log-2",
                elevatorId = "elev-1",
                maintenanceTaskId = null,
                date = LocalDate.of(2026, 10, 16),
                note = "Free-form visit note.",
                updatedAt = 7_000L,
            ),
        )

        db.elevatorTaskDao().upsertAll(listOf(task))
        db.logEntryDao().upsertAll(entries)

        assertEquals(listOf(task), db.elevatorTaskDao().getAll())
        assertEquals(entries.toSet(), db.logEntryDao().getAll().toSet())
    }

    @Test
    fun backupJsonKeepsDatesAndStatusAsPlainStrings() {
        val entry = LogEntryEntity(
            id = "log-1",
            elevatorId = "elev-1",
            maintenanceTaskId = null,
            date = LocalDate.of(2026, 10, 15),
            note = "Checked.",
            updatedAt = 6_000L,
        )
        val code = ErrorCodeEntity(
            id = "code-1",
            modelId = "model-1",
            manualId = "manual-1",
            code = "E-05",
            normalizedCode = "E05",
            meaning = "Fault",
            pageNumber = 1,
            status = ReviewStatus.REJECTED,
            updatedAt = 4_000L,
        )

        val entryJson = Json.encodeToString(entry)
        val codeJson = Json.encodeToString(code)

        assertTrue(entryJson.contains("\"date\":\"2026-10-15\""))
        assertTrue(codeJson.contains("\"status\":\"REJECTED\""))
        assertEquals(entry, Json.decodeFromString<LogEntryEntity>(entryJson))
        assertEquals(code, Json.decodeFromString<ErrorCodeEntity>(codeJson))
    }
}
