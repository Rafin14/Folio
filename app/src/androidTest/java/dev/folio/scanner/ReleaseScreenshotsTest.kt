package dev.folio.scanner

import android.graphics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.data.OcrResult
import dev.folio.scanner.ocr.*
import dev.folio.scanner.pdf.PdfWorkerDependencies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.*

/** Real application captures using generated sample text, never personal data. */
class ReleaseScreenshotsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun safeLibraryDocumentOcrAndThemeScreens() {
        val inst=InstrumentationRegistry.getInstrumentation(); val context=inst.targetContext
        val device=UiDevice.getInstance(inst)
        val docs=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).pdfs().documents
        val prefs=context.getSharedPreferences("appearance",0); val before=prefs.getString("theme","System")
        val ocrPrefs=context.getSharedPreferences("ocr-settings",0); val automatic=ocrPrefs.getBoolean("automatic",false)
        val ids=mutableListOf<String>()
        fun waitText(text:String)=compose.waitUntil(20000) { compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
        fun capture(name:String) {
            compose.waitForIdle(); device.waitForIdle()
            Thread.sleep(400) // Let the compositor settle navigation and button ripple frames.
            val file=File(context.getExternalFilesDir(null),"release-$name.png")
            assertTrue(device.takeScreenshot(file))
            device.executeShellCommand("mkdir -p /sdcard/Download/Folio-release-gallery")
            device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/Folio-release-gallery/${file.name}")
        }
        fun sample(title:String,lines:List<String>):String=runBlocking {
            val doc=docs.create(title); ids+=doc
            repeat(if(title=="Sample notes") 2 else 1) { index ->
                val image=Bitmap.createBitmap(900,1273,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(image); canvas.drawColor(Color.WHITE)
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=48f }
                canvas.drawText(title,65f,115f,paint); paint.textSize=30f
                lines.forEachIndexed { i,line -> canvas.drawText(line,65f,220f+i*90f,paint) }
                val bytes=try { ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() } } finally { image.recycle() }
                val page=docs.importImage(doc,ByteArrayInputStream(bytes),detectDocument=false)
                if(index==0) {
                    val p=docs.dao.page(page)!!; val hash=dev.folio.scanner.backup.hashFile(File(p.originalImageUri))
                    docs.dao.save(OcrResult(page,lines.joinToString("\n"),1,
                        regionsJson(lines.map { TextRegion(it,listOf(.1,.1,.8,.1,.8,.2,.1,.2),.9) }),
                        "complete",ocrRevision(p,hash),hash,"en","PaddleOCR",OCR_MODEL,OCR_PREPROCESS,p.width,p.height))
                }
            }; doc
        }
        try {
            prefs.edit().putString("theme","Light").commit()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Light",substring=false).performScrollTo().performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            ocrPrefs.edit().putBoolean("automatic",false).commit()
            sample("Sample notes",listOf("Offline documents, ready to read.","Keep originals for later edits.","Offline text search makes pages easier to find.","Share only the pages you choose.","Offline tools need no account."))
            sample("Reading list",listOf("One chapter at a time.","Review your notes.","Save the pages that matter."))
            compose.activityRule.scenario.recreate(); waitText("Sample notes")
            if(compose.onAllNodesWithContentDescription("Grid view").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Grid view").performClick()
            compose.waitUntil(20000) { compose.onAllNodesWithContentDescription("Document preview").filter(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Preview loaded")).fetchSemanticsNodes().size==2 }
            capture("home")
            compose.onNodeWithText("Sample notes").performClick()
            compose.onNodeWithContentDescription("Print document").assertIsDisplayed()
            compose.onNodeWithContentDescription("Preview page 1").assertIsDisplayed()
            capture("document")
            compose.onNodeWithContentDescription("Document actions").performClick()
            compose.onNodeWithText("Extract Text").performClick()
            compose.onNode(hasText("Page 1",substring=false) and hasClickAction()).performClick()
            compose.onNodeWithText("Search within text").performTextReplacement("offline")
            waitText("1 of 3"); compose.onNodeWithContentDescription("Next match").performClick(); waitText("2 of 3")
            capture("ocr-search")
            compose.onNodeWithContentDescription("Back").performClick(); compose.onNodeWithContentDescription("Back").performClick(); waitText("PDF workspace")
            compose.onNodeWithText("PDF workspace").performClick(); waitText("Generate PDF")
            capture("pdf-workspace")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("AMOLED",substring=false).performScrollTo().performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("PDF workspace").performClick()
            waitText("Generate PDF"); capture("amoled")
        } finally {
            prefs.edit().putString("theme",before).commit()
            ocrPrefs.edit().putBoolean("automatic",automatic).commit()
            runBlocking { ids.forEach { docs.purgeForTest(it) } }
        }
    }
}
