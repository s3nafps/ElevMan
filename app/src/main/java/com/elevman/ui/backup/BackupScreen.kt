package com.elevman.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elevman.backup.MergePlan
import com.elevman.backup.MergeTable
import com.elevman.backup.Overridden
import com.elevman.backup.Side
import com.elevman.ui.Tab

/**
 * The Backup tab. Export: the size first, then Share. Import: pick a file, and a valid one shows
 * [BackupPreview] in place of the rest until it is applied or cancelled. Holds no state;
 * [BackupViewModel] does.
 *
 * [onImport] opens the file picker. [onDismiss] closes the message an import ended with.
 */
@Composable
fun BackupScreen(
    state: BackupUiState,
    onShare: () -> Unit,
    onImport: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = Tab.BACKUP.label,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
                .semantics { heading() },
        )
        val step = state.importStep
        if (step is ImportStep.Preview) {
            BackupPreview(plan = step.plan, onApply = onApply, onCancel = onCancel, modifier = Modifier.weight(1f))
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(32.dp),
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                // The two sections are labelled, but not as headings: the tab's title is the screen's one heading.
                ExportSection(state, onShare)
                ImportSection(step, onImport, onDismiss)
            }
        }
    }
}

@Composable
private fun ExportSection(state: BackupUiState, onShare: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Export", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Save every manual, error code, elevator and log entry in one file to keep, or to send to another phone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val sizeBytes = state.sizeBytes
        when {
            state.estimating -> Progress("Working out the size of the backup...")
            sizeBytes != null -> Text(backupSizeText(sizeBytes), style = MaterialTheme.typography.bodyLarge)
        }
        if (state.writing) Progress("Writing the backup. A big one can take a minute.")
        state.exportError?.let { Alert(it) }
        Button(
            onClick = onShare,
            // Only once the size is on screen, so a very big backup is never a surprise.
            enabled = sizeBytes != null && !state.estimating && !state.writing,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Share backup") }
    }
}

@Composable
private fun ImportSection(step: ImportStep, onImport: () -> Unit, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Import", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Load a backup from another phone. You see what will change before anything is saved.",
            style = MaterialTheme.typography.bodyMedium,
        )
        when (step) {
            ImportStep.Reading -> Progress("Checking the backup. A big one can take a minute.")
            ImportStep.Applying -> Progress("Importing the backup. Keep the app open.")
            is ImportStep.Notice -> {
                Alert(step.message)
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Close") }
            }
            ImportStep.Idle, is ImportStep.Preview -> Unit
        }
        Button(
            onClick = onImport,
            enabled = step == ImportStep.Idle || step is ImportStep.Notice,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Import backup") }
    }
}

@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A result the person has to read. Announced to a screen reader as it appears. */
@Composable
private fun Alert(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * What importing a backup would do, before anything is saved: how many records are added, how many
 * are updated, and how many keep your version. Every record that exists on both sides with different
 * content is listed with the side that won. [onApply] imports; [onCancel] leaves everything as it was.
 *
 * The buttons stay at the bottom while the list of records scrolls.
 */
@Composable
fun BackupPreview(plan: MergePlan, onApply: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val kept = plan.overridden.count { it.winner == Side.LOCAL }
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Import preview",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Count("${plan.added} added", "New in this backup. They are copied to this phone.")
                    Count("${plan.updated} updated", "The backup has a newer version. It replaces yours.")
                    Count(
                        "$kept kept your version",
                        "Your version is newer, or the same age. It stays; the backup's is ignored.",
                    )
                    if (plan.overridden.isNotEmpty()) {
                        Text(
                            text = "Records that differ",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            items(plan.overridden, key = { "${it.table}/${it.id}" }) { record ->
                OverriddenRow(record)
                HorizontalDivider()
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("Cancel") }
            Button(
                onClick = onApply,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("Apply") }
        }
    }
}

@Composable
private fun Count(count: String, explanation: String) {
    Column {
        Text(count, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OverriddenRow(record: Overridden) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // The label is the version that lost; the winner is named in words, not by colour.
        Text(record.label, style = MaterialTheme.typography.titleSmall)
        Text(
            tableLabel(record.table),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (record.winner == Side.INCOMING) "Winner: the backup" else "Winner: your version",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** What a table's records are called to the person. */
private fun tableLabel(table: String): String = when (table) {
    MergeTable.MANUFACTURERS -> "Manufacturer"
    MergeTable.MODELS -> "Model"
    MergeTable.MANUALS -> "Manual"
    MergeTable.ERROR_CODES -> "Error code"
    MergeTable.MAINTENANCE_TASKS -> "Maintenance task"
    MergeTable.ELEVATORS -> "Elevator"
    MergeTable.ELEVATOR_TASKS -> "Elevator task"
    MergeTable.LOG_ENTRIES -> "Log entry"
    else -> table.removeSuffix("Entity")
}
