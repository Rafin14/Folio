package dev.folio.scanner

import android.graphics.Bitmap
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.itextpdf.kernel.pdf.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.*
import dev.folio.scanner.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PdfImmediateDiscardUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun eachPdfWorkerCanBeDiscardedImmediatelyWithVisibleCleanupFeedback() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        inst.uiAutomation.executeShellCommand("am start -W -n dev.folio.scanner.test/dev.folio.scanner.StorageGrantActivity").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
        val source=File(context.cacheDir,"shared-images/immediate-${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        PdfDocument(PdfWriter(source)).use {repeat(4) {_ ->it.addNewPage()}}
        val original=source.readBytes();val uri=androidx.core.content.FileProvider.getUriForFile(context,"dev.folio.scanner.files",source)
        val image=File(source.parentFile,"immediate-${UUID.randomUUID()}.jpg")
        Bitmap.createBitmap(300,450,Bitmap.Config.ARGB_8888).also {b ->try {image.outputStream().use {b.compress(Bitmap.CompressFormat.JPEG,95,it)}} finally {b.recycle()}}
        val imageUri=androidx.core.content.FileProvider.getUriForFile(context,"dev.folio.scanner.files",image)
        val tree=DocumentsContract.buildTreeDocumentUri("dev.folio.scanner.test.storage","root")
        val parent=DocumentsContract.buildDocumentUriUsingTree(tree,"root")
        val before=runBlocking {utility.documents.dao.allDocuments()}
        try {listOf("split","merge","raster","images","edit","generate").forEachIndexed {index,kind ->
            val id=runBlocking {utility.open(if(kind=="generate") "images" else kind,if(kind in listOf("images","generate")) listOf(imageUri,imageUri) else if(kind=="merge") listOf(uri,uri) else listOf(uri))}
            if(kind=="generate") utility.save(id,utility.session(id).put("kind","generate").put("images",org.json.JSONArray(listOf(image.path,image.path))).put("layouts",org.json.JSONArray(List(2) {val page=dev.folio.scanner.data.PageLayout();org.json.JSONObject().put("size",page.size).put("fit",page.fit).put("width",page.widthMm).put("height",page.heightMm)})))
            val target=if(kind in listOf("split","raster")) tree else DocumentsContract.createDocument(context.contentResolver,parent,"application/pdf","immediate-$kind.pdf")!!
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            // Hold the real resource gate to exercise cancellation before the first native call.
            val lease=CoroutineScope(Dispatchers.IO).launch {utility.withSessionResources(id) {entered.complete(Unit);release.await()}}
            var left=false;var leaves=0
            try {
                runBlocking {entered.await();utility.enqueue(id,target,kind in listOf("split","raster"),"Immediate cancellation")}
                compose.activityRule.scenario.onActivity {activity ->val model=ViewModelProvider(activity)[LibraryViewModel::class.java]
                    activity.setContent {key(id) {FolioTheme(listOf("Light","Dark","AMOLED")[index%3]) {PdfWorkspace(null,model,{left=true;leaves++},{},initialSession=id)}}}
                }
                compose.waitUntil(15000) {utility.work.getWorkInfosByTag("utility-$id").get().any {it.state==androidx.work.WorkInfo.State.RUNNING}}
                compose.waitUntil(15000) {compose.onAllNodesWithText("Exporting to device storage…").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitUntil(15000) {compose.onAllNodesWithText("Continue Processing").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Continue Processing").performClick()
                compose.waitUntil(10000) {compose.onAllNodesWithText("Continue Processing").fetchSemanticsNodes().isEmpty()}
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("Discard and Leave").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) {click ->click();click()}
                compose.waitUntil(10000) {compose.onAllNodesWithText("Cancelling safely…").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Cancelling safely…").assertIsDisplayed()
                UiDevice.getInstance(inst).takeScreenshot(File(context.getExternalFilesDir(null),"immediate-$kind-feedback.png"))
                UiDevice.getInstance(inst).pressBack();UiDevice.getInstance(inst).pressBack();assertFalse(left)
                release.complete(Unit);compose.waitUntil(30000) {left}
                compose.waitForIdle();assertEquals(1,leaves)
                assertFalse(utility.folder(id,create=false).exists());assertArrayEquals(original,source.readBytes())
                assertEquals(before,runBlocking {utility.documents.dao.allDocuments()})
                assertTrue(utility.work.getWorkInfosByTag("utility-$id").get().all {it.state==androidx.work.WorkInfo.State.CANCELLED})
            } finally {release.complete(Unit);runBlocking {lease.join();utility.cancelAndDiscard(id)};if(kind !in listOf("split","raster")) DocumentsContract.deleteDocument(context.contentResolver,target)}
        }} finally {source.delete();image.delete()}
    }
}
