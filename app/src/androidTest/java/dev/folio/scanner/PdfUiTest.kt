package dev.folio.scanner

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class PdfUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
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
