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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elevman.ui.Tab

internal const val ELEVATORS_EMPTY_MESSAGE = "No elevators yet. Tap Add elevator to enter the first one."
internal const val ELEVATORS_FAILED_MESSAGE = "Could not read the elevators."

/**
 * The Elevators tab's first screen: the elevators, grouped by site. Holds no state;
 * [ElevatorViewModel] does.
 *
 * [onOpen] opens an elevator's page; [onAdd] opens the form for a new one.
 */
@Composable
fun ElevatorsScreen(
    state: ElevatorUiState,
    onOpen: (elevatorId: String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            Text(
                text = Tab.ELEVATORS.label,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add elevator") }
        }
        when {
            !state.loaded -> Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator()
            }
            state.failed -> Message(ELEVATORS_FAILED_MESSAGE)
            state.groups.isEmpty() -> Message(ELEVATORS_EMPTY_MESSAGE)
            else -> LazyColumn {
                for (group in state.groups) {
                    item(key = "site/${group.site}") { SiteHeading(group.site) }
                    items(group.elevators, key = { "elevator/${it.id}" }) { elevator ->
                        ElevatorRow(elevator, onClick = { onOpen(elevator.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun SiteHeading(site: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = site,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { heading() },
        )
    }
}

@Composable
private fun ElevatorRow(elevator: ElevatorSummary, onClick: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            // Well over 48dp to tap.
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = "Open ${elevator.modelName}", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = elevator.modelName, style = MaterialTheme.typography.titleLarge)
        val details = listOfNotNull(
            elevator.serialNumber.takeIf { it.isNotBlank() }?.let { "Serial $it" },
            elevator.customer.takeIf { it.isNotBlank() },
        )
        Text(
            text = details.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
