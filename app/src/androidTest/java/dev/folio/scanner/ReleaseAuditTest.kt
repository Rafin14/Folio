package dev.folio.scanner

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Rendered release review of production navigation; no authenticated cloud operations. */
class ReleaseAuditTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun capture(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.waitForIdle()
        Thread.sleep(350)
        val folder = File(context.getExternalFilesDir(null), "v2-audit").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(folder, "$name.png")))
    }
    private fun text(label: String) = compose.onNodeWithText(label, substring = false)
    private fun back() = compose.onNodeWithContentDescription("Back").performClick()
    private fun workspaceItem(label: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label, substring = false))
    }

    @Test fun settingsWorkspaceAndSourceDialogsAcrossThemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("appearance", 0)
        val original = prefs.getString("theme", "System")
        try {
            for (theme in listOf("Light", "Dark", "AMOLED")) {
                compose.onNodeWithContentDescription("Settings").performClick()
                text(theme).performScrollTo().performClick()
                text("Appearance").performScrollTo(); capture("$theme-settings-top")
                text("Google Drive Backup").performScrollTo(); capture("$theme-settings-middle")
                text("Private by default").performScrollTo(); capture("$theme-settings-bottom")
                text("Text recognition").performScrollTo().performClick()
                capture("$theme-ocr-settings-top")
                compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().firstOrNull()?.let {
                    compose.onAllNodes(hasScrollAction())[0].performTouchInput { swipeUp() }
                    capture("$theme-ocr-settings-bottom")
                }
                back()
                text("Google Drive Backup").performScrollTo().performClick()
                capture("$theme-backup-disconnected-top")
                compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().firstOrNull()?.let {
                    compose.onAllNodes(hasScrollAction())[0].performTouchInput { swipeUp() }
                    capture("$theme-backup-disconnected-bottom")
                }
                back(); back(); capture("$theme-home")
                assertTrue("New document needs a native accessibility label in $theme",UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).hasObject(By.desc("New document")))
                text("PDF workspace").performClick()
                workspaceItem("Split PDF"); capture("$theme-workspace-top")
                workspaceItem("Generate PDF"); capture("$theme-workspace-middle")
                workspaceItem("PDF licenses and attribution"); capture("$theme-workspace-bottom")
                text("PDF licenses and attribution").performClick(); capture("$theme-licenses-dialog")
                text("Close").performClick()
                workspaceItem("Edit PDF"); text("Edit PDF").performClick(); capture("$theme-edit-source-dialog")
                text("Cancel").performClick()
                workspaceItem("Generate PDF"); text("Generate PDF").performClick()
                capture("$theme-folio-page-chooser")
                val actionBounds = compose.onNode(hasClickAction() and hasText("Continue", substring=false)).fetchSemanticsNode().boundsInRoot
                val minimumHeight = 48f * context.resources.displayMetrics.density
                assertTrue("Chooser action must retain its complete touch target: ${actionBounds.height} / $minimumHeight", actionBounds.height >= minimumHeight - 1f)
                compose.onNodeWithContentDescription("Close Folio selection").performClick(); back()
            }
        } finally {
            prefs.edit().putString("theme", original).commit()
        }
    }
}
