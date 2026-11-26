package dev.folio.scanner

import android.graphics.*
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.data.OcrResult
import dev.folio.scanner.ocr.*
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.ui.DocumentEditor
import dev.folio.scanner.ui.FolioTheme
import dev.folio.scanner.ui.LibraryViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.*

/** Real application captures using generated sample text, never personal data. */
class ReleaseScreenshotsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun libraryDateFilterAndStarPinFollowTheCurrentList() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val repository = EntryPointAccessors.fromApplication(context, PdfWorkerDependencies::class.java).pdfs().documents
        val month = LocalDate.now().withDayOfMonth(1)
        val preferences = context.getSharedPreferences("appearance", 0)
        val theme = preferences.getString("theme", "System")
        val grid = preferences.getBoolean("libraryGrid", false)
        val ids = mutableListOf<String>()
        fun capture(name: String) {
            compose.waitForIdle()
            Thread.sleep(350)
            val path = File(context.getExternalFilesDir(null), "library-$name.png")
            assertTrue(UiDevice.getInstance(inst).takeScreenshot(path))
        }
        try {
            val cover = Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888)
            val image = try {
                val ink = Paint().apply { color = Color.BLACK; textSize = 20f }
                val canvas = Canvas(cover).apply { drawColor(Color.WHITE) }
                canvas.drawText("FOLIO SAMPLE", 20f, 44f, ink)
                repeat(12) { row -> canvas.drawRect(20f, 65f + row * 23, 275f, 68f + row * 23, ink) }
                ByteArrayOutputStream().use { cover.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            } finally { cover.recycle() }
            val names = listOf("Design notes", "Reading list", "Project outline", "Travel checklist")
            runBlocking {
                names.forEachIndexed { index, title ->
                    val id = repository.create(title)
                    ids += id
                    repeat(index + 1) { repository.importImage(id, ByteArrayInputStream(image), detectDocument = false) }
                    val date = month.withDayOfMonth(if (index == 0) 3 else 20)
                        .atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val doc = repository.dao.document(id)!!
                    repository.dao.save(doc.copy(createdAt = date))
                }
            }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("AMOLED", substring = false).performScrollTo().performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            if (compose.onAllNodesWithContentDescription("List view").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithContentDescription("List view").performClick()
            }
            val notes = compose.onNodeWithText("Design notes")
            compose.waitUntil(15000) { notes.isDisplayed() }
            compose.onNodeWithText("Modified", substring = false).performClick()
            compose.onNodeWithText("Name", substring = false).performClick()
            compose.onNodeWithContentDescription("Star Project outline").performClick()
            compose.waitUntil(15000) { compose.onNodeWithContentDescription("Unstar Project outline").isDisplayed() }
            assertTrue(compose.onNodeWithText("Project outline").fetchSemanticsNode().boundsInRoot.top < notes.fetchSemanticsNode().boundsInRoot.top)
            compose.waitUntil(15000) {
                compose.onAllNodesWithContentDescription("Document preview")
                    .filter(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Preview loaded")).fetchSemanticsNodes().size >= 3
            }
            capture("redesign")
            compose.onNodeWithContentDescription("Grid view").performClick()
            compose.onNodeWithContentDescription("Unstar Project outline").assertIsDisplayed()
            capture("starred-grid")
            compose.onNodeWithContentDescription("List view").performClick()
            compose.onNodeWithContentDescription("Actions for Project outline").performClick()
            compose.onNodeWithText("Remove favorite").performScrollTo().assertIsDisplayed()
            capture("overflow")
            val closeMenu = compose.onNodeWithContentDescription("Close menu")
            compose.mainClock.autoAdvance = false
            try {
                closeMenu.performClick().assertExists()
            } finally {
                compose.mainClock.autoAdvance = true
            }
            closeMenu.assertDoesNotExist()
            compose.onNodeWithContentDescription("Filter by date").performClick()
            compose.onNodeWithText("Created date").performClick().assertIsSelected()
            capture("date-picker")
            compose.onNodeWithContentDescription("Switch to text input mode").performClick()
            val day = month.withDayOfMonth(3).format(DateTimeFormatter.ofPattern("MMddyyyy"))
            compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput(day)
            UiDevice.getInstance(inst).pressBack()
            compose.onNodeWithText("Apply date").performClick()
            notes.assertIsDisplayed()
            compose.onNodeWithText("Project outline").assertDoesNotExist()
            capture("dated-results")
            compose.onNodeWithContentDescription("Clear date filter").performClick()
            compose.onNodeWithText("Project outline").assertIsDisplayed()
            compose.onNodeWithContentDescription("Unstar Project outline").performClick()
            compose.waitUntil(15000) { compose.onNodeWithContentDescription("Star Project outline").isDisplayed() }
            assertTrue(notes.fetchSemanticsNode().boundsInRoot.top < compose.onNodeWithText("Project outline").fetchSemanticsNode().boundsInRoot.top)
        } finally {
            preferences.edit().putString("theme", theme).putBoolean("libraryGrid", grid).commit()
            runBlocking { ids.forEach { repository.purgeForTest(it) } }
        }
    }

    @Test fun amoledHeaderAndGallerySampleTheScrollingDocuments() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 31)
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val docs = EntryPointAccessors.fromApplication(context, PdfWorkerDependencies::class.java).pdfs().documents
        val prefs = context.getSharedPreferences("appearance", 0)
        val before = prefs.all
        val ids = mutableListOf<String>()
        val bitmap = Bitmap.createBitmap(600, 840, Bitmap.Config.ARGB_8888)
        val bytes = try {
            val ink = Paint().apply { color = Color.BLACK; textSize = 35f }
            val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
            canvas.drawText("SAMPLE DOCUMENT", 30f, 100f, ink)
            repeat(20) { row -> canvas.drawRect(30f, 146f + row * 28, 510f, 150f + row * 28, ink) }
            ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        } finally { bitmap.recycle() }
        fun capture(name: String): Bitmap {
            compose.waitForIdle()
            val image = requireNotNull(inst.uiAutomation.takeScreenshot())
            File(context.getExternalFilesDir(null), "glass-$name.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            return image
        }
        try {
            prefs.edit().putString("theme", "AMOLED").putBoolean("libraryGrid", true)
                .putBoolean("documentGalleryGrid", true).putBoolean("glassBlurEnabled", true)
                .putInt("glassBlurStrength", 100).commit()
            val primary = runBlocking {
                repeat(8) { index ->
                    val id = docs.create("Glass sample ${index + 1}")
                    ids += id
                    val page = docs.importImage(id, ByteArrayInputStream(bytes), detectDocument = false)
                    if (index == 7) repeat(5) { docs.duplicatePage(page) }
                }
                docs.dao.document(ids.last())!!
            }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("AMOLED", substring = false).performScrollTo().performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.activityRule.scenario.recreate()
            val rail = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText(primary.title)))
            compose.waitUntil(20000) {
                rail.isDisplayed()
            }
            val search = compose.onNodeWithText("Search documents")
            val header = compose.onNodeWithTag("library-header")
            val headerHeight = header.fetchSemanticsNode().boundsInRoot.height
            capture("library-at-rest").recycle()
            rail.performTouchInput {
                down(Offset(width * .5f, height * .70f))
                repeat(4) { moveBy(Offset(0f, -height * .02f), delayMillis = 40) }
            }
            val partialHeight = header.fetchSemanticsNode().boundsInRoot.height
            assertTrue("The header must shrink during the drag", partialHeight < headerHeight)
            header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Transitioning"))
            capture("library-mid-scroll").recycle()
            rail.performTouchInput {
                repeat(2) { moveBy(Offset(0f, height * .02f), delayMillis = 40) }
            }
            assertTrue("The header must respond to a reversal before the finger lifts",
                header.fetchSemanticsNode().boundsInRoot.height > partialHeight)
            rail.performTouchInput {
                repeat(12) { moveBy(Offset(0f, -height * .03f), delayMillis = 40) }
                up()
            }
            header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Compact"))
            search.assertIsNotDisplayed()
            val compactBounds = header.fetchSemanticsNode().boundsInRoot
            assertTrue(compactBounds.height < partialHeight)
            val scrolledHeader = capture("library-compact")
            try {
                var peak = 0
                repeat(3) { sample ->
                    compose.onNode(hasScrollAction() and hasAnyDescendant(hasContentDescription("Document preview"))).performTouchInput {
                        swipe(Offset(width * .5f, height * .64f), Offset(width * .5f, height * .60f), 400)
                    }
                    header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Compact"))
                    val movedBackdrop = capture("library-compact-moved-${sample + 1}")
                    try {
                        val region = (compactBounds.height * .2f).toInt()..(compactBounds.height * .94f).toInt()
                        peak = maxOf(peak, region.maxOf { y ->
                            (scrolledHeader.width / 20 until scrolledHeader.width * 4 / 10).maxOf { x ->
                                kotlin.math.abs(Color.red(movedBackdrop.getPixel(x, y)) - Color.red(scrolledHeader.getPixel(x, y)))
                            }
                        })
                    } finally { movedBackdrop.recycle() }
                }
                assertTrue("The pinned header must pick up moving pages under fixed chrome ($peak)", peak > 6)
            } finally { scrolledHeader.recycle() }
            compose.onNodeWithContentDescription("Open Library search").performClick()
            search.assertIsFocused()
            compose.onNodeWithContentDescription("Close search").performClick()
            compose.onNode(hasScrollAction() and hasAnyDescendant(hasContentDescription("Document preview"))).performTouchInput {
                swipe(Offset(width * .5f, height * .41f), Offset(width * .5f, height * .79f), 700)
            }
            header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
            search.assertIsDisplayed()

            val blur = mutableStateOf(true)
            compose.activityRule.scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent {
                    FolioTheme("AMOLED", blurEnabled = blur.value) {
                        DocumentEditor(primary, model, {}, {}, {}, {}, {}, {})
                    }
                }
            }
            compose.waitUntil(15000) {
                (1..6).all { page ->
                    compose.onNode(hasContentDescription("Preview page $page") and
                        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Preview loaded")).isDisplayed()
                }
            }
            val print = compose.onNodeWithContentDescription("Print document").fetchSemanticsNode().boundsInRoot
            val add = compose.onNodeWithContentDescription("Add pages").fetchSemanticsNode().boundsInRoot
            val frosted = capture("gallery-frosted")
            compose.runOnUiThread { blur.value = false }
            val opaque = capture("gallery-opaque")
            try {
                val y = print.center.y.toInt()
                val peak = (print.right.toInt() + 6 until add.left.toInt() - 6).maxOfOrNull { x ->
                    Color.red(frosted.getPixel(x, y)) - Color.red(opaque.getPixel(x, y))
                }
                assertTrue("Bright page detail must tint the tray through blur ($peak)", (peak ?: 0) > 20)
            } finally { frosted.recycle(); opaque.recycle() }
        } finally {
            val restore = prefs.edit()
            for (key in listOf("theme", "libraryGrid", "documentGalleryGrid", "glassBlurEnabled", "glassBlurStrength")) {
                when (val value = before[key]) {
                    is String -> restore.putString(key, value)
                    is Boolean -> restore.putBoolean(key, value)
                    is Int -> restore.putInt(key, value)
                    else -> restore.remove(key)
                }
            }
            restore.commit()
            runBlocking { ids.forEach { docs.purgeForTest(it) } }
        }
    }

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
            compose.onNodeWithContentDescription("New document").performClick()
            waitText("Name your document, then add pages with the camera.")
            capture("new-document")
            compose.onNodeWithText("Cancel", substring = false).performClick()
            compose.onNodeWithText("Sample notes").performClick()
            compose.onNodeWithContentDescription("Print document").assertIsDisplayed()
            compose.onNodeWithContentDescription("Preview page 1").assertIsDisplayed()
            capture("document")
            compose.onNodeWithContentDescription("Share document").performClick()
            waitText("Share or export")
            compose.onNodeWithText("Share as PDF").assertIsDisplayed().assertIsEnabled()
            capture("share")
            compose.onNodeWithText("Share as Images").performScrollTo()
            capture("share-images")
            device.pressBack()
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
