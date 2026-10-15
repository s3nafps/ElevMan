package com.elevman.domain

import java.time.LocalDate

/**
 * Returns the date a maintenance task is next due.
 *
 * The base is [lastDone] when the task has been completed before, otherwise [startDate] (the date
 * the task was added to the elevator). The result is the base plus [intervalMonths], using
 * [LocalDate.plusMonths], which clamps to the last valid day of the target month (31 Jan plus one
 * month is 28 Feb, or 29 Feb in a leap year).
 *
 * @throws IllegalArgumentException if [intervalMonths] is less than 1.
 */
fun nextDueDate(startDate: LocalDate, lastDone: LocalDate?, intervalMonths: Int): LocalDate {
    require(intervalMonths >= 1) { "intervalMonths must be at least 1 but was $intervalMonths" }
    return (lastDone ?: startDate).plusMonths(intervalMonths.toLong())
}

/** A task is overdue only once its due date has passed, so a task due [today] is not overdue. */
fun isOverdue(due: LocalDate, today: LocalDate): Boolean = due.isBefore(today)
