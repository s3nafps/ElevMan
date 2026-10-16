package com.elevman.ui.review

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elevman.data.ReviewItem

/** What a card shows for a suggestion nobody has accepted yet. */
internal const val SUGGESTED_BADGE = "Suggested"

// The page picture beside each card.
internal val ThumbnailWidth = 96.dp
internal val ThumbnailHeight = 128.dp

// Well over the 48dp a finger needs.
private val ButtonHeight = 48.dp

/**
 * The suggestions of one manual, one card each, in the order of [items]. The screen never sorts
 * them: the order is the review repository's.
 *
 * Holds no state of its own except whether the confirm question is open. Every change goes out
 * through a callback. [onAcceptAll] is called only after the person has pressed Confirm; Cancel
 * closes the question and changes nothing.
 *
 * [thumbnail] draws the page a suggestion came from. It is a slot so that the PDF rendering, which
 * needs a device, stays out of this screen. Tapping the picture calls [onOpenPage], when given.
 * [message] is shown above the list, for example when a change could not be saved.
 */
@Composable
fun ReviewScreen(
    items: List<ReviewItem>,
    onAccept: (ReviewItem) -> Unit,
    onEdit: (ReviewItem) -> Unit,
    onReject: (ReviewItem) -> Unit,
    onAcceptAll: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: @Composable (ReviewItem) -> Unit = { NoPreview() },
    onOpenPage: ((ReviewItem) -> Unit)? = null,
    message: String? = null,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = if (items.isEmpty()) "All checked" else "${items.size} to check",
                style = MaterialTheme.typography.titleMedium,
            )
            Button(
                onClick = { confirming = true },
                enabled = items.isNotEmpty(),
                modifier = Modifier.heightIn(min = ButtonHeight),
            ) { Text("Accept all") }
        }
        if (message != null) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (items.isEmpty()) {
            Text(
                text = "Nothing is waiting for review.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { "${it.kind}/${it.id}" }) { item ->
                    SuggestionCard(
                        item = item,
                        thumbnail = thumbnail,
                        onOpenPage = onOpenPage,
                        onAccept = { onAccept(item) },
                        onEdit = { onEdit(item) },
                        onReject = { onReject(item) },
                    )
                }
            }
        }
    }

    if (confirming && items.isNotEmpty()) {
        ConfirmAcceptAll(
            count = items.size,
            onConfirm = {
                confirming = false
                onAcceptAll()
            },
            onCancel = { confirming = false },
        )
    }
}

/** The second tap Accept all needs: it says how many suggestions it will confirm. */
@Composable
private fun ConfirmAcceptAll(count: Int, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val noun = if (count == 1) "suggestion" else "suggestions"
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Confirm $count $noun?") },
        text = { Text("They will all count as checked. Review them first if you are not sure.") },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.heightIn(min = ButtonHeight)) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = ButtonHeight)) { Text("Cancel") }
        },
    )
}

@Composable
private fun SuggestionCard(
    item: ReviewItem,
    thumbnail: @Composable (ReviewItem) -> Unit,
    onOpenPage: ((ReviewItem) -> Unit)?,
    onAccept: () -> Unit,
    onEdit: () -> Unit,
    onReject: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val pictureModifier = Modifier.size(ThumbnailWidth, ThumbnailHeight)
                Box(
                    modifier = if (onOpenPage == null) {
                        pictureModifier
                    } else {
                        pictureModifier.clickable(onClickLabel = "Open page ${item.pageNumber}") { onOpenPage(item) }
                    },
                ) { thumbnail(item) }
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(text = item.detail, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Page ${item.pageNumber}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SuggestedBadge()
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                // The text says what the button does; the description adds which item, for a screen reader.
                Button(
                    onClick = onAccept,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = ButtonHeight)
                        .semantics { contentDescription = "Accept ${item.label}" },
                ) { Text("Accept") }
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = ButtonHeight)
                        .semantics { contentDescription = "Edit ${item.label}" },
                ) { Text("Edit") }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = ButtonHeight)
                        .semantics { contentDescription = "Reject ${item.label}" },
                ) { Text("Reject") }
            }
        }
    }
}

/** The warning on a card: in words, so it does not depend on colour. */
@Composable
private fun SuggestedBadge() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = SUGGESTED_BADGE,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** An outlined box standing in for a page picture that is not there (yet, or at all). */
@Composable
internal fun NoPreview(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
    ) {
        Text(
            text = "No preview",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
