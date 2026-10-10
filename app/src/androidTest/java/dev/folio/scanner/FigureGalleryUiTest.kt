package dev.folio.scanner

import android.graphics.*
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.pdfanalysis.*
import dev.folio.scanner.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class FigureGalleryUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun selectionBoundaryEditingThemesAndBatchExportAreFunctional() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        inst.uiAutomation.executeShellCommand("am start -W -n dev.folio.scanner.test/dev.folio.scanner.StorageGrantActivity").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val id=UUID.randomUUID().toString();val folder=utility.folder(id)
        val bitmap=Bitmap.createBitmap(600,300,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.RED)}
        val bytes=ByteArrayOutputStream().apply {bitmap.compress(Bitmap.CompressFormat.JPEG,95,this)}.toByteArray();bitmap.recycle()
        PdfDocument(PdfWriter(File(folder,"input-0"))).use {PdfCanvas(it.addNewPage()).addImageFittedIntoRectangle(ImageDataFactory.create(bytes),Rectangle(50f,500f,200f,100f),false)}
        utility.save(id,JSONObject().put("kind","analysis").put("analysisTool","figures").put("counts",JSONArray(listOf(1))).put("names",JSONArray(listOf("Figure acceptance $id.pdf"))).put("state","editing"))
        PdfiumDocument(File(folder,"input-0")).use {pdf ->val image=pdf.render(0,842);try {File(folder,"analysis-0.jpg").outputStream().use {image.compress(Bitmap.CompressFormat.JPEG,95,it)};val inspection=pdf.inspect(0,image.width,image.height)
            File(folder,"analysis-0.json").writeText(AnalysisPage(0,image.width,image.height,595.0,842.0,emptyList(),inspection.getJSONArray("objects"),"CPUExecutionProvider").json().toString())
        } finally {image.recycle()}}
        val tree=DocumentsContract.buildTreeDocumentUri("dev.folio.scanner.test.storage","root")
        val prefs=context.getSharedPreferences("pdf-utility-destinations",0);val previous=prefs.getString("figures",null)
        var exported:android.net.Uri?=null
        try {
            val job=utility.analyze(id,true);runBlocking {withTimeout(30000) {utility.work.getWorkInfoByIdFlow(job).first {it?.state?.isFinished==true}}}
            listOf("Light","Dark","AMOLED").forEach {theme ->
                compose.activityRule.scenario.onActivity {activity ->val model=ViewModelProvider(activity)[LibraryViewModel::class.java];activity.setContent {key(theme) {FolioTheme(theme) {PdfAnalysisScreen(model,"figures",id) {}}}}}
                compose.waitUntil(15000) {compose.onAllNodesWithText("0 of 1 selected").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Select all").performClick();compose.onNodeWithText("1 of 1 selected").assertIsDisplayed()
                compose.onNodeWithText("Export selected figures").assertIsEnabled()
                compose.onNodeWithContentDescription("Preview figure 0-0").performClick()
                compose.onNodeWithText("Apply bounds").assertIsDisplayed();compose.onNodeWithText("Cancel",substring=false).performClick()
                compose.waitForIdle();Thread.sleep(350)
                inst.uiAutomation.takeScreenshot().let {b ->File(context.getExternalFilesDir(null),"major-update-figures-$theme.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            }
            assertTrue(utility.rememberDestination("figures",tree))
            compose.onNodeWithText("Export selected figures").performClick()
            val result=runBlocking {withTimeout(30000) {utility.work.getWorkInfosByTagFlow("analysis-export-$id").first {it.isNotEmpty() && it.all {j ->j.state.isFinished}}.single()}}
            assertEquals(result.outputData.toString(),androidx.work.WorkInfo.State.SUCCEEDED,result.state)
            val request=JSONObject(File(folder,"analysis-export.json").readText());exported=android.net.Uri.parse(request.getString("outputFolder"))
            val uri=android.net.Uri.parse(request.getJSONObject("outputs").getString("0"))
            assertArrayEquals(bytes,context.contentResolver.openInputStream(uri)!!.use {it.readBytes()})
            compose.onNodeWithContentDescription("Preview figure 0-0").performClick()
            compose.onNodeWithContentDescription("Adjust figure boundary").performTouchInput {
                val p=AnalysisPage.parse(JSONObject(File(folder,"analysis-0.json").readText()));val b=reconcileFigures(p).single().box
                val fit=FitTransform.create(p.width,p.height,width.toFloat(),height.toFloat())
                swipe(androidx.compose.ui.geometry.Offset(fit.x+b.right*fit.scale,fit.y+b.bottom*fit.scale),androidx.compose.ui.geometry.Offset(fit.x+(b.right-100)*fit.scale,fit.y+(b.bottom-60)*fit.scale),600)
            }
            compose.onNodeWithText("PNG · rendered").assertIsDisplayed();compose.onNodeWithText("Apply bounds").performClick()
            compose.waitUntil(10000) {File(folder,"figure-bounds.json").isFile}
            val adjusted=boxFrom(JSONObject(File(folder,"figure-bounds.json").readText()).getJSONArray("0-0"))
            assertTrue(adjusted.width<reconcileFigures(AnalysisPage.parse(JSONObject(File(folder,"analysis-0.json").readText()))).single().box.width)
            compose.onNodeWithText("Deselect all").performClick();compose.onNodeWithText("Export selected figures").assertIsNotEnabled()
        } finally {
            runBlocking {utility.work.cancelAllWorkByTag("analysis-$id").result.get();utility.work.cancelAllWorkByTag("analysis-export-$id").result.get();utility.discard(id)}
            exported?.let {DocumentsContract.deleteDocument(context.contentResolver,it)}
            prefs.edit().apply {if(previous==null) remove("figures") else putString("figures",previous)}.commit()
        }
    }
}
