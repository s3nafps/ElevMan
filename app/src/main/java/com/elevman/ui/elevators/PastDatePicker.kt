package com.elevman.ui.elevators

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import com.elevman.domain.canMarkDone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// A date picker counts days as UTC midnight, whatever the phone's time zone is.
private fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun pickerMillisToDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/**
 * The days a date picker lets the person choose: [today] and every day before it, none after. The
 * rule is [canMarkDone], the same one the view model applies before it saves.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal class NotAfter(private val today: LocalDate) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        canMarkDone(pickerMillisToDate(utcTimeMillis), today)

    // A year after this one has no selectable day, so it is not offered.
    override fun isSelectableYear(year: Int): Boolean = year <= today.year
}

/**
 * A date picker in a dialog that opens on [initial] (today unless told otherwise) and does not let
 * the person pick a day after [today]: those days are disabled, and OK stays off if the chosen day
 * is one of them. [onConfirm] gets the chosen date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PastDatePickerDialog(
    today: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    initial: LocalDate = today,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.toPickerMillis(),
        selectableDates = NotAfter(today),
    )
    val chosen = pickerState.selectedDateMillis?.let(::pickerMillisToDate)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { chosen?.let(onConfirm) },
                enabled = chosen != null && canMarkDone(chosen, today),
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState)
    }
}
