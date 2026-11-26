package dev.folio.scanner

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class ManagedPdfUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun importIsVisibleAndEditPdfOffersStorageOrManagedPdfs() {
        compose.onNodeWithText("Import",substring=false).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("PDF workspace").performClick()
        compose.onNodeWithText("Edit PDF",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Select from Storage").assertIsDisplayed()
        compose.onNodeWithText("Folio Documents").performClick()
        compose.onNodeWithText("Folio PDFs").assertIsDisplayed()
    }
    @Test fun workspaceSwipesBothWaysWithoutActivatingInSourceSelection() {
        compose.onRoot().performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width*.2f,height*.5f),androidx.compose.ui.geometry.Offset(width*.8f,height*.5f)) }
        compose.onNodeWithText("Split PDF").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width*.8f,height*.5f),androidx.compose.ui.geometry.Offset(width*.2f,height*.5f)) }
        compose.onNodeWithText("Import",substring=false).assertIsDisplayed()
        compose.onNodeWithText("PDF workspace").performClick()
        compose.onNodeWithText("Edit PDF",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Folio Documents").assertIsDisplayed()
    }

    @Test fun workspaceSlidesWhileTheDockStaysAnchored() {
        val workspace = compose.onNodeWithText("PDF workspace")
        val dockStart = workspace.fetchSemanticsNode().boundsInRoot.center.x
        compose.mainClock.autoAdvance = false
        try {
            workspace.performClick()
            compose.mainClock.advanceTimeBy(96)
            val tool = compose.onNodeWithText("Split PDF")
            val entering = tool.getUnclippedBoundsInRoot().left
            val movingDock = compose.onAllNodesWithText("PDF workspace").filter(hasClickAction()).onFirst()
            org.junit.Assert.assertEquals(dockStart, movingDock.fetchSemanticsNode().boundsInRoot.center.x, 1f)
            compose.mainClock.advanceTimeBy(400)
            val settled = tool.getUnclippedBoundsInRoot().left
            org.junit.Assert.assertTrue("Workspace enters from the left", entering < settled)
        } finally { compose.mainClock.autoAdvance = true }
    }
}
