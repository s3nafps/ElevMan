package com.elevman.ui.library

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elevman.data.ErrorCodeEntity
import com.elevman.data.MaintenanceTaskEntity
import com.elevman.data.ReviewStatus
import com.elevman.ui.theme.ElevManTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// A tall screen, so that a whole model page is on screen: a lazy list only builds what is visible.
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h1200dp")
class LibraryScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val overview = LibraryUiState(
        loaded = true,
        manufacturers = listOf(
            ManufacturerGroup("mk-o", "Otis", listOf(ModelSummary("m-gen2", "Gen2", toReview = 3), ModelSummary("m-alpha", "Alpha", 0))),
            ManufacturerGroup("mk-s", "Schindler", listOf(ModelSummary("m-3300", "3300", 12))),
        ),
    )

    private fun showOverview(
        state: LibraryUiState = overview,
        opened: MutableList<String> = mutableListOf(),
        imports: MutableList<Unit> = mutableListOf(),
    ) {
        rule.setContent {
            ElevManTheme {
                LibraryScreen(state, onOpenModel = { opened += it }, onImport = { imports += Unit })
            }
        }
    }

    @Test
    fun manufacturersAreListedWithTheirModels() {
        showOverview()

        rule.onNodeWithText("Otis").assertIsDisplayed()
        rule.onNodeWithText("Gen2").assertIsDisplayed()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Schindler").assertIsDisplayed()
        rule.onNodeWithText("3300").assertIsDisplayed()
    }

    @Test
    fun aModelWithSuggestionsWaitingShowsHowManyToReview() {
        showOverview()

        rule.onNodeWithText("3 to review").assertIsDisplayed()
        rule.onNodeWithText("12 to review").assertIsDisplayed()
    }

    @Test
    fun aModelWithNothingWaitingShowsNoCount() {
        showOverview()

        // Two of the three models have something waiting; Alpha has not.
        rule.onAllNodesWithText("to review", substring = true).assertCountEquals(2)
        rule.onNodeWithText("0 to review").assertDoesNotExist()
    }

    @Test
    fun twoManufacturersWithTheSameNameAreBothListed() {
        showOverview(
            LibraryUiState(
                loaded = true,
                manufacturers = listOf(
                    ManufacturerGroup("mk-1", "Otis", listOf(ModelSummary("m1", "Gen2", 0))),
                    ManufacturerGroup("mk-2", "Otis", listOf(ModelSummary("m2", "Alpha", 0))),
                ),
            ),
        )

        rule.onAllNodesWithText("Otis").assertCountEquals(2)
        rule.onNodeWithText("Gen2").assertIsDisplayed()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun aRealManufacturerNamedLikeTheUnknownGroupDoesNotClashWithIt() {
        // The group of models with no manufacturer row, and a real manufacturer that happens to have its name.
        showOverview(
            LibraryUiState(
                loaded = true,
                manufacturers = listOf(
                    ManufacturerGroup("mk-1", UNKNOWN_MANUFACTURER, listOf(ModelSummary("m1", "Gen2", 0))),
                    ManufacturerGroup(null, UNKNOWN_MANUFACTURER, listOf(ModelSummary("m2", "Alpha", 0))),
                ),
            ),
        )

        rule.onAllNodesWithText(UNKNOWN_MANUFACTURER).assertCountEquals(2)
        rule.onNodeWithText("Gen2").assertIsDisplayed()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun aManufacturerWhoseIdLooksLikeAKeyDoesNotClashWithTheUnknownGroup() {
        showOverview(
            LibraryUiState(
                loaded = true,
                manufacturers = listOf(
                    ManufacturerGroup("unknown-manufacturer", "Real", listOf(ModelSummary("m1", "Gen2", 0))),
                    ManufacturerGroup(null, UNKNOWN_MANUFACTURER, listOf(ModelSummary("m2", "Alpha", 0))),
                ),
            ),
        )

        rule.onNodeWithText("Real").assertIsDisplayed()
        rule.onNodeWithText(UNKNOWN_MANUFACTURER).assertIsDisplayed()
    }

    @Test
    fun tappingAModelOpensIt() {
        val opened = mutableListOf<String>()
        showOverview(opened = opened)

        rule.onNodeWithText("Alpha").performClick()

        assertEquals(listOf("m-alpha"), opened)
    }

    @Test
    fun theImportButtonStartsAnImport() {
        val imports = mutableListOf<Unit>()
        showOverview(imports = imports)

        rule.onNodeWithText("Import").performClick()

        assertEquals(1, imports.size)
    }

    @Test
    fun anEmptyLibraryTellsHowToStart() {
        showOverview(LibraryUiState(loaded = true))

        rule.onNodeWithText(LIBRARY_EMPTY_MESSAGE).assertIsDisplayed()
        rule.onNodeWithText("Import").assertIsDisplayed()
    }

    // ---- A model's page ---------------------------------------------------------------------

    private val detail = ModelDetail(
        id = "m-gen2",
        name = "Gen2",
        manufacturerName = "Otis",
        manuals = listOf(
            ManualSummary("man-1", "Service manual", 120, toReview = 2),
            ManualSummary("man-2", "Installation manual", 80, toReview = 0),
        ),
        codes = listOf(
            ErrorCodeEntity("c1", "m-gen2", "man-1", "E-21", "E21", "Door zone fault", "Check the sensor", 33, ReviewStatus.CONFIRMED, 1L),
        ),
        tasks = listOf(MaintenanceTaskEntity("t1", "m-gen2", "man-1", "Check brake", 6, 9, ReviewStatus.CONFIRMED, 1L)),
    )

    private class Taps {
        val viewed = mutableListOf<Pair<String, Int>>()
        val reviewed = mutableListOf<String>()
        val codesAdded = mutableListOf<String>()
        val tasksAdded = mutableListOf<String>()
    }

    private fun showModel(detail: ModelDetail?, loaded: Boolean = true, taps: Taps = Taps()): Taps {
        rule.setContent {
            ElevManTheme {
                ModelScreen(
                    detail = detail,
                    loaded = loaded,
                    onOpenManual = { id, page -> taps.viewed += id to page },
                    onReview = { taps.reviewed += it },
                    onAddCode = { taps.codesAdded += it },
                    onAddTask = { taps.tasksAdded += it },
                )
            }
        }
        return taps
    }

    @Test
    fun aModelPageListsManualsConfirmedCodesAndTasks() {
        showModel(detail)

        rule.onNodeWithText("Service manual").assertIsDisplayed()
        rule.onNodeWithText("120 pages").assertIsDisplayed()
        rule.onNodeWithText("Installation manual").assertIsDisplayed()
        rule.onNodeWithText("E-21").assertIsDisplayed()
        rule.onNodeWithText("Door zone fault").assertIsDisplayed()
        rule.onNodeWithText("Check brake").assertIsDisplayed()
        rule.onNodeWithText("Every 6 months").assertIsDisplayed()
    }

    @Test
    fun onlyAManualWithSuggestionsWaitingHasAReviewButton() {
        val taps = showModel(detail)

        rule.onNodeWithText("2 to review").assertIsDisplayed()
        rule.onAllNodesWithText("Review").assertCountEquals(1)
        rule.onNodeWithText("Review").performClick()

        assertEquals(listOf("man-1"), taps.reviewed)
    }

    @Test
    fun tappingAManualOpensItAtPageOne() {
        val taps = showModel(detail)

        rule.onNodeWithText("Installation manual").performClick()

        assertEquals(listOf("man-2" to 1), taps.viewed)
    }

    @Test
    fun tappingACodeOpensItsManualAtItsPage() {
        val taps = showModel(detail)

        rule.onNodeWithText("Door zone fault").performClick()

        assertEquals(listOf("man-1" to 33), taps.viewed)
    }

    @Test
    fun aModelPageWithNothingConfirmedSaysSo() {
        showModel(detail.copy(manuals = emptyList(), codes = emptyList(), tasks = emptyList()))

        rule.onNodeWithText("No manuals yet.").assertIsDisplayed()
        rule.onNodeWithText("No confirmed error codes yet.").assertIsDisplayed()
        rule.onNodeWithText("No confirmed maintenance tasks yet.").assertIsDisplayed()
    }

    @Test
    fun aModelThatIsGoneSaysSo() {
        showModel(null, loaded = true)

        rule.onNodeWithText(MODEL_GONE_MESSAGE).assertIsDisplayed()
    }

    // ---- Entering codes and tasks by hand ---------------------------------------------------

    @Test
    fun everyManualHasAnAddCodeAndAnAddTaskButton() {
        showModel(detail)

        rule.onAllNodesWithText("Add code").assertCountEquals(2)
        rule.onAllNodesWithText("Add task").assertCountEquals(2)
    }

    @Test
    fun addCodeAndAddTaskReportTheManualTheyBelongTo() {
        val taps = showModel(detail)

        // Both manuals have the buttons, and the text alone cannot tell them apart: the spoken name can.
        rule.onNodeWithContentDescription("Add error code to Installation manual").performClick()
        rule.onNodeWithContentDescription("Add maintenance task to Service manual").performClick()

        assertEquals(listOf("man-2"), taps.codesAdded)
        assertEquals(listOf("man-1"), taps.tasksAdded)
        assertTrue(taps.reviewed.isEmpty())
        assertTrue(taps.viewed.isEmpty())
    }

    @Test
    fun theAddButtonsAreThereForAManualWithNothingToReviewToo() {
        val taps = showModel(detail.copy(manuals = listOf(ManualSummary("man-9", "Brochure", 0, toReview = 0))))

        rule.onNodeWithText("Add code").performClick()
        rule.onNodeWithText("Add task").performClick()

        assertEquals(listOf("man-9"), taps.codesAdded)
        assertEquals(listOf("man-9"), taps.tasksAdded)
        rule.onNodeWithText("Review").assertDoesNotExist()
    }

    @Test
    fun aCodeEnteredWithoutAPageShowsNoPageAndOpensTheManualAtPageOne() {
        val noPage = ErrorCodeEntity("c2", "m-gen2", "man-2", "E-30", "E30", "Overspeed", "", 0, ReviewStatus.CONFIRMED, 1L)
        val taps = showModel(detail.copy(codes = listOf(noPage), tasks = emptyList()))

        rule.onNodeWithText("Overspeed").assertIsDisplayed()
        // No "Page 0" and no page at all on that row.
        rule.onAllNodesWithText("Page", substring = true).assertCountEquals(0)
        rule.onNodeWithText("Overspeed").performClick()

        assertEquals(listOf("man-2" to 1), taps.viewed)
    }
}
