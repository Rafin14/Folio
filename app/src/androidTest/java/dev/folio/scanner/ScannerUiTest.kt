package dev.folio.scanner

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test

class ScannerUiTest {
    private val testTitle="Camera workflow ${java.util.UUID.randomUUID()}"
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun captureReviewAndPersistPage() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant dev.folio.scanner android.permission.CAMERA").close()
        compose.onNodeWithText("New document", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction() and hasText("Document name")).performTextInput(testTitle)
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Add pages", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add pages", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Capture page").fetchSemanticsNodes().isNotEmpty() }
        // Camera provider readiness is not evidence of a rendered preview frame.
        compose.waitUntil(15000) {
            var streaming = false
            compose.runOnUiThread {
                streaming = preview(compose.activity.findViewById(android.R.id.content))?.previewStreamState?.value == androidx.camera.view.PreviewView.StreamState.STREAMING
            }
            streaming
        }
        compose.waitForIdle()
        snapshot("scanner")
        compose.onNodeWithText("Scan document").performTouchInput { longClick() }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Detection diagnostics",substring=true).fetchSemanticsNodes().isNotEmpty() }
        snapshot("detection-debug")
        compose.onNodeWithText("Scan document").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Capture page").performClick()
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Confirm crop") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Scan document").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        snapshot("corners")
        compose.onNodeWithText("Detect again").performClick()
        compose.waitForIdle() // Apply the asynchronous detection busy state before waiting for readiness.
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Confirm crop") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        lateinit var model:dev.folio.scanner.ui.LibraryViewModel
        compose.runOnUiThread { model=androidx.lifecycle.ViewModelProvider(compose.activity)[dev.folio.scanner.ui.LibraryViewModel::class.java] }
        // A miss shows a temporary Snackbar above the confirmation button; don't click through it.
        compose.waitUntil(10000) { model.error.value==null }
        compose.waitForIdle()
        compose.onNode(hasText("Confirm crop") and isEnabled()).performClick()
        try { compose.waitUntil(30000) { compose.onAllNodes(hasText("Accept page") and isEnabled()).fetchSemanticsNodes().isNotEmpty() } }
        catch (failure: Throwable) { snapshot("editor-failure"); compose.onRoot().printToLog("Folio camera test"); throw failure }
        compose.onNodeWithText("Grayscale").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasText("Accept page") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Accept page").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Page 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Scan document").assertCountEquals(0)
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Preview page 1").performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasText("Save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Grayscale").performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasText("Save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        snapshot("editor")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Page 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add pages",useUnmergedTree=true).performClick()
        compose.waitUntil(15000) {
            var streaming=false
            compose.runOnUiThread { streaming=preview(compose.activity.findViewById(android.R.id.content))?.previewStreamState?.value==androidx.camera.view.PreviewView.StreamState.STREAMING }
            streaming
        }
        compose.onNodeWithContentDescription("Capture page").performClick()
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Confirm crop") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Confirm crop").performClick()
        compose.waitUntil(30000) { compose.onAllNodes(hasText("Accept page") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Accept page").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("Page 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Actions for page 1").performClick()
        compose.onNodeWithText("Duplicate").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Page 3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNodeWithContentDescription("Actions for page 3").assertIsDisplayed().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Move earlier").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Move earlier").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNodeWithContentDescription("Actions for page 3").assertIsDisplayed().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Delete",substring=false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Move to Recycle Bin").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Page 3").fetchSemanticsNodes().isEmpty() }
        val device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.onNodeWithContentDescription("Share document").performClick()
        compose.onNodeWithText("Share as PDF").performScrollTo().performClick()
        compose.waitUntil(60000) { device.hasObject(androidx.test.uiautomator.By.pkg("com.android.intentresolver")) }; device.pressBack()
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Share document").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
        compose.onNodeWithContentDescription("Share document").performClick()
        compose.onNodeWithText("Share as Images").performScrollTo().performClick()
        compose.waitUntil(15000) { device.hasObject(androidx.test.uiautomator.By.pkg("com.android.intentresolver")) }; device.pressBack()
    }
    @org.junit.After fun removeOnlyThisTestsDocument() = kotlinx.coroutines.runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=dagger.hilt.android.EntryPointAccessors.fromApplication(context,dev.folio.scanner.pdf.PdfWorkerDependencies::class.java).pdfs().documents
        repo.documents.first().filter { it.title==testTitle }.forEach { repo.purgeForTest(it.id) }
    }
    private fun snapshot(name: String) {
        // Allow the headless compositor to present the already-asserted screen.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/folio-$name.png")).use { it.readBytes() }
    }
    private fun preview(view: android.view.View): androidx.camera.view.PreviewView? {
        if (view is androidx.camera.view.PreviewView) return view
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) preview(view.getChildAt(index))?.let { return it }
        return null
    }
}
