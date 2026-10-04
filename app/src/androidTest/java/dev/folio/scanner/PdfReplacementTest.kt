package dev.folio.scanner

import android.graphics.*
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.data.PageLayout
import dev.folio.scanner.pdf.*
import dev.folio.scanner.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PdfReplacementTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private val utility get()=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
    private fun pdf(file:File) { PdfDocument(PdfWriter(file)).use { doc ->
        listOf(PageSize(420f,600f) to com.itextpdf.kernel.colors.ColorConstants.RED,PageSize(720f,360f) to com.itextpdf.kernel.colors.ColorConstants.BLUE).forEach { (size,color) ->
            val page=doc.addNewPage(size); PdfCanvas(page).setFillColor(color).rectangle(0.0,0.0,size.width.toDouble(),size.height.toDouble()).fill()
        }
    } }
    private fun ready()=compose.waitUntil(30000) { compose.onAllNodes(hasText("Use page",substring=false) and isEnabled()).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
    private fun capture(name:String) { compose.waitForIdle(); Thread.sleep(350); inst.uiAutomation.takeScreenshot().let { b -> File(context.cacheDir,"replacement-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }; b.recycle() } }

    @Test fun selectedPdfPageRetainsNaturalSizeCorrectContentAndOriginalBytes() {
        val source=File(context.cacheDir,"replacement-source-${System.nanoTime()}.pdf"); pdf(source)
        val bytes=source.readBytes()
        try {
            (1..2).forEach { page ->
                val (image,layout)=runBlocking { utility.replacementPdfPage(source,page) }
                try {
                    assertEquals(if(page==1) 420.0 else 720.0,layout.widthMm*72/25.4,.001)
                    assertEquals(if(page==1) 600.0 else 360.0,layout.heightMm*72/25.4,.001)
                    val bitmap=BitmapFactory.decodeFile(image.path)
                    try { val color=bitmap.getPixel(bitmap.width/2,bitmap.height/2); if(page==1) assertTrue(Color.red(color)>240 && Color.blue(color)<20) else assertTrue(Color.blue(color)>240 && Color.red(color)<20) } finally { bitmap.recycle() }
                    val output=File(context.cacheDir,"replacement-out.pdf")
                    try { utility.engine.generate(listOf(image),output,"Natural",layouts=listOf(layout)); utility.engine.read(output).use { assertEquals(layout.widthMm*72/25.4,it.getPage(1).pageSize.width.toDouble(),.001) } } finally { output.delete() }
                } finally { image.delete() }
            }
            assertArrayEquals(bytes,source.readBytes())
        } finally { source.delete() }
    }

    @Test fun replacementPreviewControlsThemesRotationFiltersCropAndSizing() {
        val source=File(context.cacheDir,"replacement-controls.jpg")
        val bitmap=Bitmap.createBitmap(900,450,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try { source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) } } finally { bitmap.recycle() }
        var accepted:Pair<File,PageLayout>?=null
        val target=PageLayout("Custom","Fit",148.0,210.0)
        val natural=PageLayout("Custom","Fit",300.0,150.0)
        try {
            listOf("Light","Dark","AMOLED").forEach { theme ->
                compose.activityRule.scenario.onActivity { activity ->
                    val model=ViewModelProvider(activity)[LibraryViewModel::class.java]
                    activity.setContent { androidx.compose.runtime.key(theme) { FolioTheme(theme) { PdfReplacementEditor(source,model,{},target,natural) { file,layout -> accepted=file to layout } } } }
                }
                ready()
                compose.onNodeWithContentDescription("Match Page Size").assertIsOn()
                compose.onNodeWithText("Page size").assertIsNotEnabled()
                val sizeRow=compose.onNodeWithText("Page size").getUnclippedBoundsInRoot()
                var right=androidx.compose.ui.unit.Dp(0f)
                listOf("Crop","Rotate","Filter").forEach { label ->
                    val bounds=compose.onNode(hasClickAction() and hasText(label,substring=false)).assertIsDisplayed().getUnclippedBoundsInRoot()
                    assertTrue(bounds.top>=sizeRow.bottom); assertTrue((bounds.bottom-bounds.top).value>=48f); assertTrue(bounds.left>=right); right=bounds.right
                }
                val matched=compose.onNodeWithContentDescription("Replacement page sizing").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                val pixels=Regex("preview ([0-9]+)x([0-9]+)").find(matched)!!
                assertEquals(148.0/210,pixels.groupValues[1].toDouble()/pixels.groupValues[2].toDouble(),.002)
                val button=compose.onNode(hasClickAction() and hasText("Use page",substring=false)).fetchSemanticsNode().boundsInRoot
                val dynamic=if(theme=="Light") androidx.compose.material3.dynamicLightColorScheme(context) else androidx.compose.material3.dynamicDarkColorScheme(context)
                val screen=inst.uiAutomation.takeScreenshot()
                try { assertEquals("System dynamic primary",dynamic.primary.toArgb(),screen.getPixel(button.center.x.toInt(),(button.top+button.height*.18f).toInt())) } finally { screen.recycle() }
                capture("$theme-matched")
                compose.onNodeWithContentDescription("Match Page Size").performClick(); ready()
                compose.onNodeWithContentDescription("Match Page Size").assertIsOff()
                compose.onNodeWithText("Page size").assertIsEnabled()
                var description=compose.onNodeWithContentDescription("Replacement page sizing").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                assertTrue(description.contains("Natural") && description.contains("300.0, 150.0"))
                capture("$theme-natural")
                if(theme=="Light") {
                    compose.onNodeWithText("Rotate",substring=false).performClick(); ready()
                    description=compose.onNodeWithContentDescription("Replacement page sizing").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                    assertTrue(description.contains("150.0, 300.0") && description.contains("90"))
                    compose.onNodeWithText("Filter",substring=false).performClick()
                    compose.onNodeWithText("Grayscale",substring=false).performScrollTo().performClick()
                    compose.onNodeWithText("Done",substring=false).performClick(); ready()
                    description=compose.onNodeWithContentDescription("Replacement page sizing").fetchSemanticsNode().config[SemanticsProperties.StateDescription]; assertTrue(description.contains("Grayscale"))
                    compose.onNodeWithText("Crop",substring=false).performClick()
                    compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Crop corners").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithContentDescription("Crop corners").performTouchInput { val r=fit(width.toFloat(),height.toFloat(),2f,24f*context.resources.displayMetrics.density); down(androidx.compose.ui.geometry.Offset(r[0],r[1])); moveBy(androidx.compose.ui.geometry.Offset(r[2]*.12f,r[3]*.12f),700); up() }
                    compose.onNodeWithText("Confirm crop").performClick(); ready()
                    compose.onNodeWithText("Page size").performClick()
                    compose.onNodeWithText("A5",substring=false).performScrollTo().performClick()
                    compose.onNodeWithText("Apply page size",substring=false).performScrollTo().performClick(); ready()
                    capture("Light-edited")
                    compose.onNodeWithText("Use page",substring=false).performClick()
                    compose.waitUntil(30000) { accepted!=null }
                    val (image,layout)=accepted!!; assertEquals("A5",layout.size)
                    val output=File(context.cacheDir,"replacement-controls.pdf")
                    try {
                        utility.engine.generate(listOf(image),output,"Replacement",layouts=listOf(layout))
                        utility.engine.read(output).use { assertEquals(148*72/25.4,it.getPage(1).pageSize.width.toDouble(),.01) }
                        android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(output,android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { assertEquals(1,it.pageCount) }
                    } finally { image.delete(); output.delete() }
                }
            }
        } finally { source.delete() }
    }

    @Test fun nativeDevicePdfPickerSelectsSecondPageMatchesTargetAndKeepsSources() {
        val source=File(context.cacheDir,"shared-images/replace-target-${System.nanoTime()}.pdf").apply { parentFile!!.mkdirs() }; pdf(source)
        val replacement=File(context.cacheDir,"replacement-device.pdf"); pdf(replacement)
        val bytes=source.readBytes(); val replacementBytes=replacement.readBytes()
        val before=runBlocking { utility.documents.dao.allDocuments() }
        val id=runBlocking { utility.open("edit",listOf(androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",source))) }
        val name="replace-pdf-${System.nanoTime()}.pdf"
        val uri=context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name); put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"application/pdf"); put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/") })!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(replacementBytes) }
        var imageUri:Uri?=null
        try {
            compose.activityRule.scenario.onActivity { activity ->
                val model=ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent { FolioTheme { PdfUtilityEditor(id,utility.pages(id),model,{},{},{}) } }
            }
            compose.waitUntil(20000) { compose.onAllNodesWithText("Replace",substring=false).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Replace",substring=false).performClick(); compose.onNodeWithText("Choose device image or PDF").performClick()
            val device=UiDevice.getInstance(inst)
            fun clickNative(selector:BySelector):Boolean {
                repeat(3) {
                    device.waitForIdle()
                    val node=device.wait(Until.findObject(selector),5000) ?: return false
                    try { node.click(); return true } catch(_:StaleObjectException) { Thread.sleep(250) }
                }
                return false
            }
            device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),10000)
            Thread.sleep(500)
            if(!device.hasObject(By.text(name))) { clickNative(By.desc("Show roots")); clickNative(By.text("Downloads")) }
            assertTrue("Pick native PDF",clickNative(By.text(name)))
            device.wait(Until.findObject(By.text("Open")),1000)?.click()
            compose.waitUntil(20000) { compose.onAllNodesWithText("Use selected PDF page").filter(isEnabled()).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            compose.onNodeWithContentDescription("Next PDF page").performClick()
            compose.waitUntil(20000) { compose.onAllNodesWithContentDescription("Replacement PDF page 2 preview").fetchSemanticsNodes().isNotEmpty() }
            capture("selected-pdf-page-2")
            compose.onNodeWithText("Use selected PDF page").performClick(); ready()
            compose.onNodeWithContentDescription("Match Page Size").assertIsOn(); compose.onNodeWithText("Use page").performClick()
            compose.waitUntil(30000) { utility.pages(id)[0].replacement.isNotEmpty() }
            val result=File(utility.pages(id)[0].replacement)
            utility.engine.read(result).use { assertEquals(420.0,it.getPage(1).pageSize.width.toDouble(),.001); assertEquals(600.0,it.getPage(1).pageSize.height.toDouble(),.001) }
            val image=utility.engine.render(result,1,context.cacheDir)
            try { val color=image.getPixel(image.width/2,image.height/2); assertTrue(Color.blue(color)>240 && Color.red(color)<20); assertEquals(Color.WHITE,image.getPixel(image.width/2,image.height/10)) } finally { image.recycle() }
            assertArrayEquals(bytes,source.readBytes()); assertArrayEquals(replacementBytes,replacement.readBytes())
            assertEquals(before,runBlocking { utility.documents.dao.allDocuments() })
            assertTrue(utility.pages(id)[1].replacement.isEmpty())
            val imageName="replace-image-${System.nanoTime()}.jpg"
            imageUri=context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,imageName); put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"image/jpeg"); put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/") })!!
            val sourceImage=Bitmap.createBitmap(500,1000,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val imageBytes=try { java.io.ByteArrayOutputStream().use { sourceImage.compress(Bitmap.CompressFormat.JPEG,95,it); it.toByteArray() } } finally { sourceImage.recycle() }
            context.contentResolver.openOutputStream(imageUri!!)!!.use { it.write(imageBytes) }
            compose.waitUntil(20000) { compose.onAllNodesWithText("Replace",substring=false).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Replace",substring=false).performClick(); compose.onNodeWithText("Choose device image or PDF").performClick()
            device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),10000); Thread.sleep(500)
            if(!device.hasObject(By.text(imageName))) { clickNative(By.desc("Show roots")); clickNative(By.text("Downloads")) }
            assertTrue("Pick native image",clickNative(By.text(imageName)))
            device.wait(Until.findObject(By.text("Open")),1000)?.click()
            compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("Crop corners").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            compose.onNodeWithText("Confirm crop").performClick(); ready()
            compose.onNodeWithContentDescription("Match Page Size").performClick(); ready(); capture("device-image-natural")
            val previous=result.path
            compose.onNodeWithText("Use page").performClick()
            compose.waitUntil(30000) { utility.pages(id)[0].replacement!=previous }
            utility.engine.read(File(utility.pages(id)[0].replacement)).use { val size=it.getPage(1).pageSize; assertEquals(.5,size.width.toDouble()/size.height,.005) }
            context.contentResolver.openInputStream(imageUri!!)!!.use { assertArrayEquals(imageBytes,it.readBytes()) }
            assertEquals(before,runBlocking { utility.documents.dao.allDocuments() }); assertTrue(utility.pages(id)[1].replacement.isEmpty())

        } finally { capture("native-final"); UiDevice.getInstance(inst).dumpWindowHierarchy(File(context.cacheDir,"replacement-native.xml")); imageUri?.let { context.contentResolver.delete(it,null,null) }; context.contentResolver.delete(uri,null,null); runBlocking { utility.discard(id) }; source.delete(); replacement.delete() }
    }
}
