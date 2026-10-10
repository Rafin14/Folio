package dev.folio.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.*
import dev.folio.scanner.pdfanalysis.*
import dev.folio.scanner.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ActiveAnalysisDiscardUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun realOcrWorkerContinueThenDiscardDoesNotCrashOrDeleteSource() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val image=File(context.cacheDir,"cancel-ui-${UUID.randomUUID()}.jpg")
        val bitmap=Bitmap.createBitmap(1400,1800,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=50f}
        val canvas=Canvas(bitmap);repeat(8) {canvas.drawText("Folio offline cancellation document $it",70f,160f+it*150,paint)}
        try {image.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,96,it)}} finally {bitmap.recycle()}
        val source=File(context.cacheDir,"shared-images/cancel-ui-${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        runBlocking {utility.engine.generate(List(12) {image},source,"Cancellation UI",PdfQuality.ORIGINAL)}
        val original=source.readBytes();val before=runBlocking {utility.documents.dao.allDocuments()}
        val id=runBlocking {utility.open("analysis",listOf(androidx.core.content.FileProvider.getUriForFile(context,"dev.folio.scanner.files",source)))}
        var left=false;var leaves=0
        try {
            compose.activityRule.scenario.onActivity {activity ->val model=ViewModelProvider(activity)[LibraryViewModel::class.java];activity.setContent {FolioTheme("AMOLED") {PdfAnalysisScreen(model,"ocr",id) {left=true;leaves++}}}}
            compose.waitUntil(15000) {compose.onAllNodesWithText("OCR Current Page").fetchSemanticsNodes().isNotEmpty()}
            val work=utility.analyze(id,cpu=true,mode=OcrMode.FULL_PAGE,replaceOcr=true)
            compose.waitUntil(30000) {utility.work.getWorkInfoById(work).get()?.progress?.getString("stage")?.contains("Full Page OCR")==true}
            UiDevice.getInstance(inst).pressBack()
            compose.waitUntil(10000) {runCatching {compose.onNodeWithText("Continue Processing").assertIsDisplayed()}.isSuccess}
            compose.onNodeWithText("Continue Processing").assertIsDisplayed().performClick()
            assertFalse(left)
            compose.waitUntil(10000) {compose.onAllNodesWithText("Continue Processing").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntil(10000) {runCatching {compose.onNodeWithText("Discard and Leave").assertIsDisplayed()}.isSuccess}
            compose.onNodeWithText("Discard and Leave").assertIsDisplayed()
            compose.waitForIdle();UiDevice.getInstance(inst).takeScreenshot(File(context.getExternalFilesDir(null),"active-ocr-discard-dialog.png"))
            compose.onNodeWithText("Discard and Leave").performSemanticsAction(SemanticsActions.OnClick) {click ->click();click()}
            // Repeated Back while cancellation is waiting is absorbed by the leaving state.
            UiDevice.getInstance(inst).pressBack();UiDevice.getInstance(inst).pressBack()
            compose.waitUntil(60000) {left}
            compose.waitForIdle();assertEquals(1,leaves)
            assertFalse(utility.folder(id,create=false).exists())
            assertArrayEquals(original,source.readBytes());assertEquals(before,runBlocking {utility.documents.dao.allDocuments()})
            assertEquals(androidx.work.WorkInfo.State.CANCELLED,utility.work.getWorkInfoById(work).get()!!.state)
        } finally {runBlocking {utility.cancelAndDiscard(id)};source.delete();image.delete()}
    }
}
