package dev.folio.scanner

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PdfUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun sharedDockSelectsEitherDestinationAndCreatesFromLibrary() {
        val title = "Dock test ${java.util.UUID.randomUUID()}"
        val repo = EntryPointAccessors.fromApplication(InstrumentationRegistry.getInstrumentation().targetContext,
            PdfWorkerDependencies::class.java).pdfs().documents
        try {
            for (theme in listOf("Light", "Dark", "AMOLED", "System")) {
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText(theme, substring = false).performScrollTo().performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("Library").assertIsSelected()
                compose.onNodeWithText("Import").assertIsDisplayed()
                compose.onNodeWithText("PDF workspace").performClick()
                compose.onNode(hasText("PDF workspace") and isSelected()).assertIsDisplayed()
                compose.onNodeWithContentDescription("New document").assertDoesNotExist()
                compose.onNodeWithText("Library").assertIsNotSelected().performClick()
            }
            val workspaceTab = compose.onNodeWithText("PDF workspace")
            val libraryTab = compose.onNodeWithText("Library")
            val create = compose.onNodeWithContentDescription("New document").fetchSemanticsNode().boundsInRoot
            val leftTab = workspaceTab.fetchSemanticsNode().boundsInRoot
            val rightTab = libraryTab.fetchSemanticsNode().boundsInRoot
            assertTrue("PDF workspace should be the left tab", leftTab.right <= rightTab.left)
            assertTrue("New document should sit above the trailing tab", create.bottom <= rightTab.top && create.center.x > leftTab.center.x)
            compose.onNodeWithText("New document").performClick()
            compose.onNode(hasSetTextAction() and hasText("Document name")).performTextInput(title)
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(20000) { compose.onAllNodesWithContentDescription("Add pages").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Library").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Library").assertIsSelected()
        } finally {
            runBlocking { repo.documents.first().filter { it.title == title }.forEach { repo.purgeForTest(it.id) } }
        }
    }

    @Test fun workspaceIsAUtilityWithoutImportOrSavedFiles() {
        compose.onNodeWithText("PDF workspace").performClick()
        listOf("Split PDF","Merge PDF","Image to PDF","PDF to Image","Edit PDF","Generate PDF").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onAllNodesWithText("Saved PDFs").assertCountEquals(0)
        compose.onAllNodesWithText("Import PDF").assertCountEquals(0)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle(); Thread.sleep(300)
        val instrument=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrument.uiAutomation.takeScreenshot().let { image -> java.io.File(instrument.targetContext.cacheDir,"utility-home-return.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; image.recycle() }
        compose.onRoot().printToLog("Utility return")
        compose.onNodeWithText("New document",useUnmergedTree=true).assertIsDisplayed()
    }
}
