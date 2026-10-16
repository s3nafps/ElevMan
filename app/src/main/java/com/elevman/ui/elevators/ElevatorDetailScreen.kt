package com.elevman.ui.elevators

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elevman.data.DueItem
import com.elevman.domain.canMarkDone
import java.time.LocalDate

internal const val ELEVATOR_GONE_MESSAGE = "This elevator is no longer in the app."
internal const val NOTHING_TO_ASSIGN_MESSAGE =
    "No confirmed tasks are left to assign for this model. Confirm tasks from its manual in the Library first."

/**
 * One elevator: its model and serial number, its tasks with their due dates (overdue ones first,
 * each marked with the word "Overdue"), and its log. Holds no state but which dialog is open;
 * [ElevatorViewModel] holds the rest.
 *
 * [today] says what today is when a date picker opens. [onMarkDone] gets the elevator task and the
 * date the person picked, and [onAddNote] the date and the text of a note.
 */
@Composable
fun ElevatorDetailScreen(
    detail: ElevatorDetail?,
    loaded: Boolean,
    today: () -> LocalDate,
    onMarkDone: (elevatorTaskId: String, date: LocalDate) -> Unit,
    onAddNote: (date: LocalDate, note: String) -> Unit,
    onAssign: (maintenanceTaskId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        detail != null -> DetailContent(detail, today, onMarkDone, onAddNote, onAssign, modifier)
        !loaded -> Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize()) {
            CircularProgressIndicator()
        }
        else -> Text(
            text = ELEVATOR_GONE_MESSAGE,
            style = MaterialTheme.typography.bodyLarge,
            modifier = modifier.padding(16.dp),
        )
    }
}

@Composable
private fun DetailContent(
    detail: ElevatorDetail,
    today: () -> LocalDate,
    onMarkDone: (String, LocalDate) -> Unit,
    onAddNote: (LocalDate, String) -> Unit,
    onAssign: (String) -> Unit,
    modifier: Modifier,
) {
    // Which dialog is open. The task is kept by id, so a list that reloads under the dialog is no problem.
    var markDoneTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var assigning by rememberSaveable { mutableStateOf(false) }
    var noting by rememberSaveable { mutableStateOf(false) }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item { Facts(detail) }
        item {
            SectionHeader(title = "Tasks", action = "Assign task", onAction = { assigning = true })
        }
        if (detail.tasks.isEmpty()) {
            item { Empty("No tasks assigned yet.") }
        }
        items(detail.tasks, key = { "task/${it.elevatorTaskId}" }) { task ->
            TaskRow(task, onMarkDone = { markDoneTaskId = task.elevatorTaskId })
            HorizontalDivider()
        }
        item {
            SectionHeader(title = "Log", action = "Add note", onAction = { noting = true })
        }
        if (detail.log.isEmpty()) {
            item { Empty("No log entries yet.") }
        }
        items(detail.log, key = { "log/${it.id}" }) { line ->
            LogRow(line)
            HorizontalDivider()
        }
    }

    markDoneTaskId?.let { taskId ->
        // Today as it is now, when the picker opens.
        val now = remember(taskId) { today() }
        PastDatePickerDialog(
            today = now,
            onConfirm = { date ->
                markDoneTaskId = null
                onMarkDone(taskId, date)
            },
            onDismiss = { markDoneTaskId = null },
        )
    }
    if (assigning) {
        AssignTaskDialog(
            choices = detail.assignable,
            onAssign = { id ->
                assigning = false
                onAssign(id)
            },
            onDismiss = { assigning = false },
        )
    }
    if (noting) {
        AddNoteDialog(
            today = remember { today() },
            onSave = { date, note ->
                noting = false
                onAddNote(date, note)
            },
            onDismiss = { noting = false },
        )
    }
}

@Composable
private fun Facts(detail: ElevatorDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(16.dp)) {
        Text(detail.modelName, style = MaterialTheme.typography.titleLarge)
        Text("Serial number: ${detail.serialNumber}", style = MaterialTheme.typography.bodyLarge)
        Text("Site: ${detail.site}", style = MaterialTheme.typography.bodyLarge)
        if (detail.customer.isNotBlank()) {
            Text("Customer: ${detail.customer}", style = MaterialTheme.typography.bodyLarge)
        }
        if (detail.notes.isNotBlank()) {
            Text(detail.notes, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String, onAction: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun TaskRow(task: DueItem, onMarkDone: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(task.taskName, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Due ${task.due}", style = MaterialTheme.typography.bodyLarge)
            if (task.overdue) OverdueLabel()
        }
        OutlinedButton(
            onClick = onMarkDone,
            // Several rows have the same button: a screen reader hears which task it is for.
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Mark ${task.taskName} done" },
        ) { Text("Mark done") }
    }
}

/** The word "Overdue" in a tinted box. The word carries the meaning, so it does not depend on colour. */
@Composable
private fun OverdueLabel() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = "Overdue",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LogRow(line: LogLine) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("${line.date} · ${line.taskName ?: "Note"}", style = MaterialTheme.typography.titleSmall)
        if (line.note.isNotBlank()) Text(line.note, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The tasks of the elevator's model that it does not have yet; tapping one assigns it. */
@Composable
private fun AssignTaskDialog(choices: List<TaskChoice>, onAssign: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign task") },
        text = {
            if (choices.isEmpty()) {
                Text(NOTHING_TO_ASSIGN_MESSAGE)
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    for (choice in choices) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable(onClickLabel = "Assign ${choice.name}") { onAssign(choice.id) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(choice.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Every ${choice.intervalMonths} months",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A note for the log, dated [today] unless changed. A date after [today] cannot be chosen. */
@Composable
private fun AddNoteDialog(today: LocalDate, onSave: (LocalDate, String) -> Unit, onDismiss: () -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    // Kept as a day number so that it survives the screen turning, like the text.
    var epochDay by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val date = LocalDate.ofEpochDay(epochDay)
    var changingDate by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Date: $date", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { changingDate = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Change date")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(date, note) },
                enabled = note.isNotBlank() && canMarkDone(date, today),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (changingDate) {
        PastDatePickerDialog(
            today = today,
            initial = date,
            onConfirm = {
                epochDay = it.toEpochDay()
                changingDate = false
            },
            onDismiss = { changingDate = false },
        )
    }
}
