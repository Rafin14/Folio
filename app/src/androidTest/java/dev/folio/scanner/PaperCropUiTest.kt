package dev.folio.scanner

import android.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.data.decodeCorners
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.*

class PaperCropUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun everyPaperPreviewOrientationAndFitChangeAndEdgeCornerMovesWithoutSnapping() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val repo=EntryPointAccessors.fromApplication(inst.targetContext,PdfWorkerDependencies::class.java).pdfs().documents
        val doc=runBlocking { repo.create("Paper crop precision") }
        val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes=try { ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() } } finally { image.recycle() }
        fun waitDescription(name:String) { compose.waitUntil(30000) { compose.onAllNodesWithContentDescription(name).fetchSemanticsNodes().isNotEmpty() } }
        fun capture(name:String) { UiDevice.getInstance(inst).takeScreenshot(File(inst.targetContext.getExternalFilesDir(null),"folio-paper-$name.png")) }
        try {
            val id=runBlocking { repo.importImage(doc,ByteArrayInputStream(bytes),detectDocument=false) }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Paper crop precision").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Paper crop precision").performClick(); compose.onNodeWithContentDescription("Preview page 1").performClick()
            waitDescription("Enhanced page preview"); compose.onNodeWithText("Page size").performScrollTo().performClick()
            val ratios=listOf("A4" to 210.0/297,"Letter" to 215.9/279.4,"A5" to 148.0/210,"Legal" to 215.9/355.6,"Square" to 1.0,"Original / Auto" to 400.0/600)
            ratios.forEach { (name,ratio) ->
                compose.onNodeWithText(name).performScrollTo().performClick()
                val description=compose.onNodeWithContentDescription("Page size preview").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                assertEquals(ratio.toFloat(),description.substringAfter("aspect ").toFloat(),.001f)
                val pixels=compose.onNodeWithContentDescription("Page size preview").captureToImage().toPixelMap()
                assertTrue((0 until pixels.height).any { y -> (0 until pixels.width).any { x -> pixels[x,y].blue>.8f && pixels[x,y].red<.2f } })
                capture(name.replace(" / ","-"))
            }
            compose.onNodeWithText("A4").performScrollTo().performClick(); compose.onNodeWithText("Landscape").performClick()
            val landscape=compose.onNodeWithContentDescription("Page size preview").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            assertEquals((297.0/210).toFloat(),landscape.substringAfter("aspect ").toFloat(),.001f)
            compose.onNodeWithText("Fill / crop").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Page size preview").assert(SemanticsMatcher("Fill mode preview") { it.config[SemanticsProperties.StateDescription].contains("Fill") })
            val filled=compose.onNodeWithContentDescription("Page size preview").captureToImage().toPixelMap()
            assertTrue("Fill must draw content across the landscape page",filled[filled.width/3,filled.height/2].blue>.8f && filled[filled.width/3,filled.height/2].red<.2f)
            capture("landscape-fill")
            compose.onNodeWithText("Custom").performScrollTo().performClick()
            compose.onNodeWithText("Width (mm)").performScrollTo().performTextReplacement("300")
            compose.onNodeWithText("Height (mm)").performScrollTo().performTextReplacement("400")
            val custom=compose.onNodeWithContentDescription("Page size preview").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            assertEquals(.75f,custom.substringAfter("aspect ").toFloat(),.001f)
            compose.onNodeWithText("Apply page size").performScrollTo().performClick()
            compose.onNodeWithText("Crop",substring=false).performScrollTo().performClick(); waitDescription("Crop corners")
            val density=compose.activity.resources.displayMetrics.density
            compose.onNodeWithContentDescription("Crop corners").performTouchInput {
                val rect=dev.folio.scanner.ui.fit(width.toFloat(),height.toFloat(),400f/600,24f*density)
                down(Offset(rect[0],rect[1])); moveTo(Offset(rect[0]+rect[2]*.01f,rect[1]+rect[3]*.01f))
            }
            compose.onNodeWithContentDescription("Crop corners").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Magnifying corner 1")); capture("magnifier")
            compose.onNodeWithContentDescription("Crop corners").performTouchInput { up() }
            compose.onNodeWithContentDescription("Crop corners").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Zoom 1.0 times"))
            compose.onNodeWithText("Confirm crop").performClick()
            compose.waitUntil(20000) { runBlocking { decodeCorners(repo.dao.page(id)!!.crop).first().x>0.0 } }
            val points=runBlocking { decodeCorners(repo.dao.page(id)!!.crop) }
            assertTrue("Sub-1.5% movement must not snap back to zero",points[0].x>.003 && points[0].x<.015 && points[0].y>.003 && points[0].y<.015)
        } finally { runBlocking { repo.purgeForTest(doc) } }
    }
}
