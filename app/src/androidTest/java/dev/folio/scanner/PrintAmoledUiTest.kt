package dev.folio.scanner

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.data.PageLayout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.*

class PrintAmoledUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun amoledPersistsDuplicateNameIsInlineAndSelectedPrintOpensNativePreview() {
        val inst=InstrumentationRegistry.getInstrumentation(); val context=inst.targetContext
        val docs=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).pdfs().documents
        val device=UiDevice.getInstance(inst)
        val doc=runBlocking { docs.create("Print AMOLED acceptance") }
        fun screenshot(name:String) {
            compose.waitForIdle(); device.waitForIdle()
            val file=File(context.getExternalFilesDir(null),"folio-print-amoled-$name.png")
            assertTrue(device.takeScreenshot(file))
            device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/${file.name}")
        }
        try {
            val bitmap=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE); val canvas=android.graphics.Canvas(this); val paint=android.graphics.Paint().apply { color=Color.BLACK; textSize=32f }
                canvas.drawText("PRINT ACCEPTANCE",32f,64f,paint)
            }
            val bytes=try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it); it.toByteArray() } } finally { bitmap.recycle() }
            val page=runBlocking { val first=docs.importImage(doc,ByteArrayInputStream(bytes),detectDocument=false); docs.edit(first,layout=PageLayout("A5"),enqueueOcr=false); docs.duplicatePage(first); first }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("AMOLED").performScrollTo().performClick()
            compose.waitForIdle(); screenshot("settings")
            assertEquals("AMOLED",context.getSharedPreferences("appearance",0).getString("theme",""))
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("AMOLED").assertIsDisplayed()
            compose.runOnUiThread {
                val controller=androidx.core.view.WindowCompat.getInsetsController(compose.activity.window,compose.activity.window.decorView)
                assertFalse(controller.isAppearanceLightStatusBars); assertFalse(controller.isAppearanceLightNavigationBars)
            }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("New document",useUnmergedTree=true).performClick()
            compose.onNodeWithText("Document name").performTextInput("  print amoled acceptance  ")
            compose.onNodeWithText("A document named “Print AMOLED acceptance” already exists. Choose another name.").assertIsDisplayed()
            compose.onNodeWithText("Save").assertIsNotEnabled(); screenshot("duplicate-name")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Print AMOLED acceptance").performClick()
            compose.onNodeWithContentDescription("Print document").performClick()
            compose.onNodeWithText("All pages (2)").assertIsDisplayed()
            compose.onNodeWithText("Open Android print preview").assertIsEnabled()
            compose.onNodeWithText("Selected pages").performClick()
            // Radio labels remain readable; click its sibling radio via semantic selection.
            if(compose.onAllNodesWithText("Clear all").fetchSemanticsNodes().isEmpty()) compose.onAllNodes(isSelectable()).filter(isNotSelected()).onFirst().performClick()
            compose.onNodeWithText("Clear all").performClick()
            compose.onNodeWithText("0 selected").assertIsDisplayed(); compose.onNodeWithText("Open Android print preview").assertIsNotEnabled()
            compose.onNodeWithText("Select all").performClick(); compose.onNodeWithText("2 selected").assertIsDisplayed()
            compose.onAllNodes(isToggleable()).onLast().performClick()
            compose.onNodeWithText("1 selected").assertIsDisplayed(); screenshot("page-selection")
            compose.onNodeWithText("Open Android print preview").performClick()
            compose.waitUntil(60000) { device.hasObject(By.pkg("com.android.printspooler")) }
            if(device.hasObject(By.text("Select a printer"))) {
                device.findObject(By.text("Select a printer")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Save as PDF")),10000)); device.findObject(By.text("Save as PDF")).click()
            }
            assertTrue(device.wait(Until.hasObject(By.text("Save as PDF")),20000))
            assertTrue(device.wait(Until.hasObject(By.text("1/1")),15000))
            assertTrue(device.hasObject(By.text("ISO A5"))); screenshot("native-preview")
            device.pressBack()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Document actions").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            compose.onNodeWithContentDescription("Print document").performClick()
            compose.onNodeWithText("All pages (2)").assertIsDisplayed(); compose.onNodeWithText("Open Android print preview").performClick()
            compose.waitUntil(60000) { device.hasObject(By.pkg("com.android.printspooler")) }
            if(device.hasObject(By.text("Select a printer"))) {
                device.findObject(By.text("Select a printer")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Save as PDF")),10000)); device.findObject(By.text("Save as PDF")).click()
            }
            assertTrue(device.wait(Until.hasObject(By.text("Save as PDF")),20000))
            assertTrue(device.wait(Until.hasObject(By.text("1/2")),15000))
            assertTrue(device.hasObject(By.text("ISO A5"))); screenshot("native-all-pages")
            device.pressBack()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Document actions").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            compose.onNodeWithContentDescription("Preview page 1").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("Enhanced page preview").fetchSemanticsNodes().isNotEmpty() }
            screenshot("editor")
            device.pressBack()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Document actions").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Back").performClick()
            runBlocking { docs.delete(doc) }
            compose.onNodeWithContentDescription("Recycle Bin").performClick()
            compose.onNodeWithText("Deletes permanently in 60 days").assertIsDisplayed(); screenshot("recycle")
        } finally {
            runBlocking { docs.dao.document(doc)?.let { if(it.trashedAt==null) docs.delete(doc); docs.permanentlyDelete(setOf(doc)) } }
            context.getSharedPreferences("appearance",0).edit().putString("theme","System").commit()
        }
    }
}
