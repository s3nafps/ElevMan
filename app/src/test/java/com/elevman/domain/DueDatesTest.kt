package com.elevman.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DueDatesTest {

    @Test
    fun monthEndClampsToValidDate() {
        val due = nextDueDate(
            startDate = LocalDate.of(2026, 1, 1),
            lastDone = LocalDate.of(2026, 1, 31),
            intervalMonths = 1,
        )
        assertEquals(LocalDate.of(2026, 2, 28), due)
    }

    @Test
    fun leapYearMonthEnd() {
        val due = nextDueDate(
            startDate = LocalDate.of(2028, 1, 1),
            lastDone = LocalDate.of(2028, 1, 31),
            intervalMonths = 1,
        )
        assertEquals(LocalDate.of(2028, 2, 29), due)
    }

    @Test
    fun neverDoneUsesStartDate() {
        val due = nextDueDate(
            startDate = LocalDate.of(2026, 3, 10),
            lastDone = null,
            intervalMonths = 6,
        )
        assertEquals(LocalDate.of(2026, 9, 10), due)
    }

    @Test
    fun dueTodayIsNotOverdue() {
        val today = LocalDate.of(2026, 10, 15)
        assertFalse(isOverdue(due = today, today = today))
    }

    @Test
    fun dueYesterdayIsOverdue() {
        val today = LocalDate.of(2026, 10, 15)
        assertTrue(isOverdue(due = today.minusDays(1), today = today))
    }

    @Test
    fun dueTomorrowIsNotOverdue() {
        val today = LocalDate.of(2026, 10, 15)
        assertFalse(isOverdue(due = today.plusDays(1), today = today))
    }

    @Test
    fun zeroIntervalIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            nextDueDate(LocalDate.of(2026, 1, 1), null, 0)
        }
    }

    @Test
    fun negativeIntervalIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            nextDueDate(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), -1)
        }
    }
}
