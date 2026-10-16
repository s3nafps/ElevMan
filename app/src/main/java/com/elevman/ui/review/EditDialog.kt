package com.elevman.ui.review

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** The fields of the item being corrected, and which item it is. */
sealed interface EditTarget {
    val id: String

    /** An error code: what is printed, what it means, and what to do about it. */
    data class Code(
        override val id: String,
        val code: String,
        val meaning: String,
        val causeAndFix: String,
    ) : EditTarget

    /** A maintenance task: its name and how often it is done. */
    data class Task(
        override val id: String,
        val name: String,
        val intervalMonths: Int,
    ) : EditTarget
}

internal const val INTERVAL_TOO_SMALL_MESSAGE = "Interval must be at least 1 month."
internal const val CODE_REQUIRED_MESSAGE = "Enter the code."
internal const val NAME_REQUIRED_MESSAGE = "Enter a name."

// Well over the 48dp a finger needs.
private val ButtonHeight = 48.dp

/**
 * A dialog to correct one suggestion. It checks the fields itself: [onSave] is called only with
 * values the repository will accept (a task interval of at least 1 month, and a code or name that
 * is not empty), as a copy of [target] with the new values. Nothing is changed until then.
 */
@Composable
internal fun EditDialog(
    target: EditTarget,
    onSave: (EditTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    when (target) {
        is EditTarget.Code -> EditCodeDialog(target, onSave, onDismiss)
        is EditTarget.Task -> EditTaskDialog(target, onSave, onDismiss)
    }
}

@Composable
private fun EditCodeDialog(target: EditTarget.Code, onSave: (EditTarget) -> Unit, onDismiss: () -> Unit) {
    var code by rememberSaveable(target.id) { mutableStateOf(target.code) }
    var meaning by rememberSaveable(target.id) { mutableStateOf(target.meaning) }
    var causeAndFix by rememberSaveable(target.id) { mutableStateOf(target.causeAndFix) }
    var codeMissing by rememberSaveable(target.id) { mutableStateOf(false) }

    DialogFrame(
        title = "Edit error code",
        onSave = {
            if (code.isBlank()) {
                codeMissing = true
            } else {
                onSave(target.copy(code = code.trim(), meaning = meaning.trim(), causeAndFix = causeAndFix.trim()))
            }
        },
        onDismiss = onDismiss,
    ) {
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
            onValueChange = { meaning = it },
            label = { Text("Meaning") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = causeAndFix,
            onValueChange = { causeAndFix = it },
            label = { Text("Cause and fix") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun EditTaskDialog(target: EditTarget.Task, onSave: (EditTarget) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(target.id) { mutableStateOf(target.name) }
    var interval by rememberSaveable(target.id) { mutableStateOf(target.intervalMonths.toString()) }
    var nameMissing by rememberSaveable(target.id) { mutableStateOf(false) }
    var intervalRejected by rememberSaveable(target.id) { mutableStateOf(false) }

    DialogFrame(
        title = "Edit maintenance task",
        onSave = {
            val months = interval.trim().toIntOrNull()
            nameMissing = name.isBlank()
            // Not a whole number, or below 1: turned back here, before the repository is asked.
            intervalRejected = months == null || months < 1
            if (!nameMissing && !intervalRejected && months != null) {
                onSave(target.copy(name = name.trim(), intervalMonths = months))
            }
        },
        onDismiss = onDismiss,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                nameMissing = false
            },
            label = { Text("Name") },
            singleLine = true,
            isError = nameMissing,
            supportingText = if (nameMissing) ({ Text(NAME_REQUIRED_MESSAGE) }) else null,
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
    }
}

/** The dialog's title, a scrolling body (the keyboard takes much of a small screen), and its two buttons. */
@Composable
private fun DialogFrame(
    title: String,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    fields: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) { fields() }
        },
        confirmButton = {
            Button(onClick = onSave, modifier = Modifier.heightIn(min = ButtonHeight)) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = ButtonHeight)) { Text("Cancel") }
        },
    )
}
