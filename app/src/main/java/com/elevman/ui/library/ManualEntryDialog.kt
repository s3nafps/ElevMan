package com.elevman.ui.library

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.elevman.domain.normalizeCode
import com.elevman.ui.review.CODE_REQUIRED_MESSAGE
import com.elevman.ui.review.DialogFrame
import com.elevman.ui.review.EditDialog
import com.elevman.ui.review.EditTarget
import com.elevman.ui.review.INTERVAL_TOO_SMALL_MESSAGE
import com.elevman.ui.review.validInterval

internal const val MEANING_REQUIRED_MESSAGE = "Enter the meaning."
internal const val TASK_NAME_REQUIRED_MESSAGE = "Enter the task name."

// A page number longer than this is a slip of the finger, and keeps the number inside an Int.
private const val MAX_PAGE_DIGITS = 5

/**
 * The entry form or edit dialog that is open, if any, on top of the model page. [error] is the
 * message of a save that failed; the form stays open and keeps what was typed. [onDismiss] closes
 * it without saving. The edit dialog is the review's own ([EditDialog]), so a typo in a code or task
 * is corrected the same way there and here.
 */
@Composable
internal fun ManualEntryDialogs(
    entry: ManualEntry?,
    editing: EditTarget?,
    error: String?,
    onSaveCode: (NewCode) -> Unit,
    onSaveTask: (NewTask) -> Unit,
    onSaveEdit: (EditTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    when (entry) {
        null -> Unit
        is ManualEntry.Code -> AddCodeDialog(entry, onSaveCode, onDismiss, error)
        is ManualEntry.Task -> AddTaskDialog(entry, onSaveTask, onDismiss, error)
    }
    if (editing != null) EditDialog(target = editing, onSave = onSaveEdit, onDismiss = onDismiss, error = error)
}

/**
 * The form to type in an error code of [entry]'s manual. It checks the fields itself, with the
 * edit dialog's frame and messages: [onSave] is called only with a code that has a letter or digit
 * in it and a meaning that is not blank, trimmed, and a page of 0 when the page was left empty.
 * Nothing is saved until then.
 *
 * [error] is shown inside the form, for a save that was refused after [onSave].
 */
@Composable
internal fun AddCodeDialog(
    entry: ManualEntry.Code,
    onSave: (NewCode) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
) {
    var code by rememberSaveable(entry.manualId) { mutableStateOf("") }
    var meaning by rememberSaveable(entry.manualId) { mutableStateOf("") }
    var causeAndFix by rememberSaveable(entry.manualId) { mutableStateOf("") }
    var page by rememberSaveable(entry.manualId) { mutableStateOf("") }
    var codeMissing by rememberSaveable(entry.manualId) { mutableStateOf(false) }
    var meaningMissing by rememberSaveable(entry.manualId) { mutableStateOf(false) }

    DialogFrame(
        title = "Add error code",
        onSave = {
            // Blank, or only dashes and spaces: normalized it is empty, and no search could find it.
            codeMissing = normalizeCode(code).isEmpty()
            meaningMissing = meaning.isBlank()
            if (!codeMissing && !meaningMissing) {
                onSave(NewCode(code.trim(), meaning.trim(), causeAndFix.trim(), pageNumberOf(page)))
            }
        },
        onDismiss = onDismiss,
        error = error,
        // Four fields and a manual line: on a small screen with the keyboard up the message must not scroll away.
        errorPinned = true,
    ) {
        ManualName(entry)
        OutlinedTextField(
            value = code,
            onValueChange = {
                code = it
                codeMissing = false
            },
            label = { Text("Code") },
            singleLine = true,
            isError = codeMissing,
            supportingText = if (codeMissing) ({ Text(CODE_REQUIRED_MESSAGE) }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = meaning,
            onValueChange = {
                meaning = it
                meaningMissing = false
            },
            label = { Text("Meaning") },
            isError = meaningMissing,
            supportingText = if (meaningMissing) ({ Text(MEANING_REQUIRED_MESSAGE) }) else null,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = causeAndFix,
            onValueChange = { causeAndFix = it },
            label = { Text("Cause and fix") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        PageField(page, onChange = { page = it })
    }
}

/**
 * The form to type in a maintenance task of [entry]'s manual. [onSave] is called only with a name
 * that is not blank and an interval of at least 1 month (the edit dialog's check and message),
 * and a page of 0 when the page was left empty. Nothing is saved until then.
 *
 * [error] is shown inside the form, for a save that was refused after [onSave].
 */
@Composable
internal fun AddTaskDialog(
    entry: ManualEntry.Task,
    onSave: (NewTask) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
) {
    var name by rememberSaveable(entry.manualId) { mutableStateOf("") }
    var interval by rememberSaveable(entry.manualId) { mutableStateOf("") }
    var page by rememberSaveable(entry.manualId) { mutableStateOf("") }
    var nameMissing by rememberSaveable(entry.manualId) { mutableStateOf(false) }
    var intervalRejected by rememberSaveable(entry.manualId) { mutableStateOf(false) }

    DialogFrame(
        title = "Add maintenance task",
        onSave = {
            val months = validInterval(interval)
            nameMissing = name.isBlank()
            // Not a whole number, or below 1: turned back here, before the repository is asked.
            intervalRejected = months == null
            if (!nameMissing && months != null) {
                onSave(NewTask(name.trim(), months, pageNumberOf(page)))
            }
        },
        onDismiss = onDismiss,
        error = error,
        // Four fields and a manual line: on a small screen with the keyboard up the message must not scroll away.
        errorPinned = true,
    ) {
        ManualName(entry)
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                nameMissing = false
            },
            label = { Text("Name") },
            singleLine = true,
            isError = nameMissing,
            supportingText = if (nameMissing) ({ Text(TASK_NAME_REQUIRED_MESSAGE) }) else null,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = interval,
            onValueChange = {
                interval = it
                intervalRejected = false
            },
            label = { Text("Interval (months)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = intervalRejected,
            supportingText = if (intervalRejected) ({ Text(INTERVAL_TOO_SMALL_MESSAGE) }) else null,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        PageField(page, onChange = { page = it })
    }
}

/** Which manual the entry goes to: there can be several on one model page. */
@Composable
private fun ManualName(entry: ManualEntry) {
    Text(
        text = "Manual: ${entry.manualTitle}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** An optional page: digits only, so what is typed is always a page number or nothing. */
@Composable
private fun PageField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(MAX_PAGE_DIGITS)) },
        label = { Text("Page (optional)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

/** The page typed in [text]; 0, which means no page, when it is empty. */
private fun pageNumberOf(text: String): Int = text.toIntOrNull() ?: 0
