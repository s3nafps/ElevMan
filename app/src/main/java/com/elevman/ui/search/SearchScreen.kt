package com.elevman.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.elevman.data.CodeHit
import com.elevman.data.ReviewStatus
import com.elevman.ui.Tab

/** What a result shows when the code has been read from the manual but nobody has accepted it yet. */
internal const val SUGGESTED_LABEL = "Suggested, not yet checked"

// A model whose row is missing comes back from the search with an empty name.
private const val UNKNOWN_MODEL = "Unknown model"

/**
 * The Search tab: a large box for an error code, and below it the matching codes grouped by
 * elevator model. Holds no state; [SearchViewModel] does.
 *
 * Tapping a result calls [onOpen] with the manual and the page the code was found on.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onOpen: (manualId: String, page: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        Text(
            text = Tab.SEARCH.label,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp, end = 16.dp)
                .semantics { heading() },
        )
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            // A large box: this is the field the whole app is opened for.
            textStyle = MaterialTheme.typography.titleLarge,
            label = { Text("Error code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        when {
            state.query.isBlank() -> Message("Type an error code, for example E-21.")
            state.groups.isNotEmpty() -> Results(state.groups, onOpen)
            // Wait for the search before saying there is nothing.
            !state.isSearching -> Message("No error code matches \"${state.query.trim()}\".")
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
private fun Results(
    groups: List<Pair<String, List<CodeHit>>>,
    onOpen: (manualId: String, page: Int) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        for ((modelName, hits) in groups) {
            item { GroupHeading(modelName.ifEmpty { UNKNOWN_MODEL }) }
            items(hits) { hit ->
                HitRow(hit, onClick = { onOpen(hit.manualId, hit.pageNumber) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun GroupHeading(name: String) {
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
private fun HitRow(hit: CodeHit, onClick: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            // Well over 48dp to tap.
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = "Open the manual at page ${hit.pageNumber}", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = hit.code,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Page ${hit.pageNumber}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text = hit.meaning, style = MaterialTheme.typography.bodyLarge)
        if (hit.status == ReviewStatus.SUGGESTED) SuggestedLabel()
    }
}

/** The warning on a result nobody has checked: in words, so it does not depend on colour. */
@Composable
private fun SuggestedLabel() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = SUGGESTED_LABEL,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
