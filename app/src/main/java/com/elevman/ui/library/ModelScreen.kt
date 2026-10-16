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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal const val MODEL_GONE_MESSAGE = "This model is no longer in the library."

/**
 * One model: its manuals, then its confirmed error codes, then its confirmed maintenance tasks.
 * Suggestions are not listed here; they are reviewed from the manual they came from.
 *
 * [detail] is null while it is being read ([loaded] false) or when the model is gone.
 * [onOpenManual] opens a manual in the viewer at a page, [onReview] opens the review of a manual.
 */
@Composable
fun ModelScreen(
    detail: ModelDetail?,
    loaded: Boolean,
    onOpenManual: (manualId: String, page: Int) -> Unit,
    onReview: (manualId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (detail == null) {
        Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize().padding(16.dp)) {
            if (loaded) Text(MODEL_GONE_MESSAGE, style = MaterialTheme.typography.bodyLarge) else CircularProgressIndicator()
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Text(
                text = detail.manufacturerName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        item { SectionHeading("Manuals") }
        if (detail.manuals.isEmpty()) item { Empty("No manuals yet.") }
        items(detail.manuals, key = { "manual/${it.id}" }) { manual ->
            ManualRow(manual, onOpen = { onOpenManual(manual.id, 1) }, onReview = { onReview(manual.id) })
            HorizontalDivider()
        }

        item { SectionHeading("Error codes") }
        if (detail.codes.isEmpty()) item { Empty("No confirmed error codes yet.") }
        items(detail.codes, key = { "code/${it.id}" }) { code ->
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(onClickLabel = "Open the manual at page ${code.pageNumber}") {
                        onOpenManual(code.manualId, code.pageNumber)
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(code.code, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Page ${code.pageNumber}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(code.meaning, style = MaterialTheme.typography.bodyLarge)
                if (code.causeAndFix.isNotBlank()) Text(code.causeAndFix, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
        }

        item { SectionHeading("Maintenance tasks") }
        if (detail.tasks.isEmpty()) item { Empty("No confirmed maintenance tasks yet.") }
        items(detail.tasks, key = { "task/${it.id}" }) { task ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(task.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Every ${task.intervalMonths} months", style = MaterialTheme.typography.bodyLarge)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { heading() },
        )
    }
}

@Composable
private fun Empty(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun ManualRow(manual: ManualSummary, onOpen: () -> Unit, onReview: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .weight(1f)
                .clickable(onClickLabel = "Open ${manual.title}", onClick = onOpen)
                .heightIn(min = 48.dp),
        ) {
            Text(manual.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = if (manual.pageCount > 0) "${manual.pageCount} pages" else "Page count unknown",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (manual.toReview > 0) Text(toReviewLabel(manual.toReview), style = MaterialTheme.typography.labelLarge)
        }
        if (manual.toReview > 0) {
            OutlinedButton(
                onClick = onReview,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .heightIn(min = 48.dp),
            ) { Text("Review") }
        }
    }
}
