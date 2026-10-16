package com.elevman.ui.library

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

/** The words next to a model that has suggestions waiting: "3 to review". */
internal fun toReviewLabel(count: Int): String = "$count to review"

internal const val LIBRARY_EMPTY_MESSAGE = "Your library is empty. Tap Import to add a PDF manual."
internal const val LIBRARY_FAILED_MESSAGE = "Could not read the library."

/**
 * The Library tab's first screen: the manufacturers, and under each the models. A model with
 * suggestions waiting shows "N to review". Holds no state; [LibraryViewModel] does.
 *
 * [onOpenModel] opens a model's page; [onImport] starts the import of a PDF.
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onOpenModel: (modelId: String) -> Unit,
    onImport: () -> Unit,
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
                text = Tab.LIBRARY.label,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Button(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp)) { Text("Import") }
        }
        when {
            !state.loaded -> Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator()
            }
            state.failed -> Message(LIBRARY_FAILED_MESSAGE)
            state.manufacturers.isEmpty() -> Message(LIBRARY_EMPTY_MESSAGE)
            else -> LazyColumn {
                for (group in state.manufacturers) {
                    item(key = headingKey(group)) { ManufacturerHeading(group.name) }
                    items(group.models, key = { "model/${it.id}" }) { model ->
                        ModelRow(model, onClick = { onOpenModel(model.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// Every key in the list starts with its kind, so a manufacturer id can never equal a model id; the
// group of models with no manufacturer has no id and a key no "maker/<id>" can be equal to.
private const val UNKNOWN_MANUFACTURER_KEY = "unknown-manufacturer"

/** Names the heading by the manufacturer's id: two manufacturers can have the same name. */
private fun headingKey(group: ManufacturerGroup): String =
    group.id?.let { "maker/$it" } ?: UNKNOWN_MANUFACTURER_KEY

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
private fun ManufacturerHeading(name: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { heading() },
        )
    }
}

@Composable
private fun ModelRow(model: ModelSummary, onClick: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Well over 48dp to tap.
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = "Open ${model.name}", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = model.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (model.toReview > 0) {
            // In words, so it does not depend on colour.
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = toReviewLabel(model.toReview),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
