package com.elevman.ui.elevators

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elevman.ui.library.ModelChoice

internal const val NO_MODELS_MESSAGE =
    "There are no models yet. Import a manual in the Library first; its model is listed here."

/**
 * The form for a new elevator: site, customer, model, serial number and notes. The model is one of
 * [models], the ones in the library. Save needs a site and a model; the rest may be left empty.
 *
 * [onSave] gets the five values as typed. [busy] is true while the previous save is still running.
 */
@Composable
fun AddElevatorScreen(
    models: List<ModelChoice>,
    busy: Boolean,
    onSave: (site: String, customer: String, modelId: String, serialNumber: String, notes: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var site by rememberSaveable { mutableStateOf("") }
    var customer by rememberSaveable { mutableStateOf("") }
    var modelId by rememberSaveable { mutableStateOf<String?>(null) }
    var serial by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }

    val chosen = modelId
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = site,
            onValueChange = { site = it },
            label = { Text("Site") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = customer,
            onValueChange = { customer = it },
            label = { Text("Customer") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Which model is it?",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(top = 8.dp)
                .semantics { heading() },
        )
        if (models.isEmpty()) {
            Text(NO_MODELS_MESSAGE, style = MaterialTheme.typography.bodyLarge)
        }
        for (model in models) {
            ModelRow(label = model.label, selected = chosen == model.id, onClick = { modelId = model.id })
        }
        OutlinedTextField(
            value = serial,
            onValueChange = { serial = it },
            label = { Text("Serial number") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { if (chosen != null) onSave(site, customer, chosen, serial, notes) },
            enabled = !busy && site.isNotBlank() && chosen != null,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(top = 8.dp),
        ) { Text("Save") }
    }
}

@Composable
private fun ModelRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Well over the 48dp a finger needs.
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
