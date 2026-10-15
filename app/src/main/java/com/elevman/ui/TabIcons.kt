package com.elevman.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The bar icons, drawn from Material icon paths (24 x 24) so the app needs no icon library.
 * They are tinted by the bar, so the fill colour here does not show.
 */
internal val Tab.icon: ImageVector
    get() = when (this) {
        Tab.SEARCH -> SearchIcon
        Tab.LIBRARY -> LibraryIcon
        Tab.ELEVATORS -> ElevatorsIcon
        Tab.BACKUP -> BackupIcon
    }

private fun icon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()

// A magnifying glass.
private val SearchIcon = icon(
    "Search",
    "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5" +
        " 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5z" +
        "M9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
)

// Three lines with bullets.
private val LibraryIcon = icon(
    "Library",
    "M3,13h2v-2L3,11v2zM3,17h2v-2L3,15v2zM3,9h2L5,7L3,7v2zM7,13h14v-2L7,11v2z" +
        "M7,17h14v-2L7,15v2zM7,7v2h14L21,7L7,7z",
)

// An arrow up beside an arrow down, like the buttons of a lift.
private val ElevatorsIcon = icon(
    "Elevators",
    "M16,17.01V10h-2v7.01h-3L15,21l4,-3.99h-3zM9,3L5,6.99h3V14h2V6.99h3L9,3z",
)

// A floppy disk: the backup is a file on the phone, not a cloud.
private val BackupIcon = icon(
    "Backup",
    "M17,3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V7l-4,-4z" +
        "M12,19c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3z" +
        "M15,9H5V5h10v4z",
)
