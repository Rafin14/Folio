package dev.folio.scanner

import android.graphics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.*

class ScanDraftUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun backConfirmsEveryUnsavedStageRecreationRetakeAndManualFallback() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val repo=EntryPointAccessors.fromApplication(instrumentation.targetContext,PdfWorkerDependencies::class.java).pdfs().documents
        val bitmap=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val bytes=try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it); it.toByteArray() } } finally { bitmap.recycle() }
        val doc=runBlocking { repo.create("Unfinished scan UI") }
        val draft=runBlocking { repo.stageScan(doc,ByteArrayInputStream(bytes)) }
        fun keepEditing(back: String) {
            compose.onNodeWithContentDescription(back).performClick()
            compose.onNodeWithText("Discard this scan?").assertIsDisplayed()
            compose.onNodeWithText("Keep editing").performClick()
        }
        fun ready() { compose.waitUntil(30000) { compose.onAllNodes(hasText("Accept page") and isEnabled()).fetchSemanticsNodes().isNotEmpty() } }
        try {
            compose.waitUntil(15000) { compose.onAllNodesWithText("Unfinished scan UI").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Unfinished scan UI").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Continue unfinished scan").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Continue unfinished scan").performClick()
            compose.waitUntil(15000) { compose.onAllNodes(hasText("Confirm crop") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            keepEditing("Cancel crop") // Original capture is unsaved even without adjustments.
            val density=compose.activity.resources.displayMetrics.density
            compose.onNodeWithContentDescription("Crop corners").performTouchInput {
                val rect=dev.folio.scanner.ui.fit(width.toFloat(),height.toFloat(),.75f,24f*density)
                swipe(androidx.compose.ui.geometry.Offset(rect[0],rect[1]),androidx.compose.ui.geometry.Offset(rect[0]+rect[2]*.08f,rect[1]+rect[3]*.08f),500)
            }
            keepEditing("Cancel crop")
            compose.onNodeWithText("Adjust").performClick()
            compose.onNodeWithContentDescription("Top left horizontal position").performSemanticsAction(SemanticsActions.SetProgress) { it(.1f) }
            compose.onNodeWithContentDescription("Top left vertical position").performSemanticsAction(SemanticsActions.SetProgress) { it(.1f) }
            compose.onNodeWithText("Done").performClick(); keepEditing("Cancel crop")
            compose.onNodeWithText("Confirm crop").performClick(); ready(); keepEditing("Cancel editing")
            compose.onNodeWithText("Grayscale").performScrollTo().performClick(); ready(); keepEditing("Cancel editing")
            compose.onNodeWithText("Adjustments").performClick()
        compose.onNodeWithContentDescription("Brightness").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }; ready(); compose.onNodeWithText("Done").performClick(); keepEditing("Cancel editing")
            compose.onNodeWithText("Rotate",substring=false).performScrollTo().performClick(); ready(); keepEditing("Cancel editing")
            compose.activityRule.scenario.recreate(); ready()
            androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Discard this scan?").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Discard this scan?").assertIsDisplayed(); compose.onNodeWithText("Keep editing").performClick()
            assertTrue(runBlocking { repo.dao.pages(doc).isEmpty() })
            assertArrayEquals(bytes,runBlocking { File(repo.draft(draft).original).readBytes() })
            compose.onNodeWithText("Retake").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Scan document").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(runBlocking { repo.drafts(doc).isEmpty() }); assertTrue(runBlocking { repo.dao.pages(doc).isEmpty() })
            compose.onNodeWithContentDescription("Cancel scanning").performClick()
            val discard=runBlocking { repo.stageScan(doc,ByteArrayInputStream(bytes)) }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Continue unfinished scan").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Continue unfinished scan").performClick()
            compose.waitUntil(15000) { compose.onAllNodes(hasText("Confirm crop") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Cancel crop").performClick(); compose.onNodeWithText("Discard",substring=false).performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Add pages",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(runBlocking { repo.dao.pages(doc).isEmpty() }); assertFalse(File(instrumentation.targetContext.filesDir,"scan-drafts/$discard").exists())
        } finally { runBlocking { repo.purgeForTest(doc) } }
    }
}
