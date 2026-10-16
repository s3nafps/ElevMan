package com.elevman.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Well over the 48dp a finger needs.
private val RowHeight = 56.dp

/**
 * The import of one PDF: first the title and the model it belongs to, then, once the importer is
 * done, the notice if there is one. An imported manual does not show here: the Library goes
 * straight to its review. Holds no state; [ImportViewModel] does.
 */
@Composable
fun ImportFlow(
    state: ImportUiState,
    onTitleChange: (String) -> Unit,
    onPickExisting: (modelId: String) -> Unit,
    onPickNew: () -> Unit,
    onNewManufacturerChange: (String) -> Unit,
    onNewModelChange: (String) -> Unit,
    onImport: () -> Unit,
    onOpenInLibrary: (modelId: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notice = state.notice
    when {
        notice != null -> ImportNoticeView(notice, onOpenInLibrary, onClose, modifier)
        state.working -> Column(
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier.fillMaxSize().padding(16.dp),
        ) {
            CircularProgressIndicator()
            Text("Importing. A long manual can take a minute.", style = MaterialTheme.typography.bodyLarge)
        }
        else -> ImportForm(
            state, onTitleChange, onPickExisting, onPickNew, onNewManufacturerChange, onNewModelChange, onImport, modifier,
        )
    }
}

@Composable
private fun ImportForm(
    state: ImportUiState,
    onTitleChange: (String) -> Unit,
    onPickExisting: (String) -> Unit,
    onPickNew: () -> Unit,
    onNewManufacturerChange: (String) -> Unit,
    onNewModelChange: (String) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = state.title,
            onValueChange = onTitleChange,
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Which model is it for?",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(top = 8.dp)
                .semantics { heading() },
        )
        for (choice in state.choices) {
            ChoiceRow(
                label = choice.label,
                selected = state.pick == ModelPick.Existing(choice.id),
                onClick = { onPickExisting(choice.id) },
            )
        }
        ChoiceRow(label = "A new model", selected = state.pick == ModelPick.New, onClick = onPickNew)
        if (state.pick == ModelPick.New) {
            OutlinedTextField(
                value = state.newManufacturer,
                onValueChange = onNewManufacturerChange,
                label = { Text("Manufacturer") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.newModel,
                onValueChange = onNewModelChange,
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = onImport,
            enabled = state.canImport,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(top = 8.dp),
        ) { Text("Import") }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * What the person is told when an import ends without a manual to review. "Open in library" shows
 * only when the notice knows which model the manual is under.
 */
@Composable
fun ImportNoticeView(
    notice: ImportNotice,
    onOpenInLibrary: (modelId: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text = notice.message, style = MaterialTheme.typography.titleLarge)
            val modelId = notice.modelId
            if (modelId != null) {
                Button(
                    onClick = { onOpenInLibrary(modelId) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Open in library") }
            }
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Close") }
        }
    }
}
