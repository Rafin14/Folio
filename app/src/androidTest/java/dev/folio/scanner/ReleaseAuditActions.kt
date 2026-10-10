package dev.folio.scanner

import android.graphics.Bitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.performClick as originalClick
import androidx.compose.ui.test.performScrollTo as originalScroll
import androidx.compose.ui.test.performScrollToNode as originalScrollToNode
import androidx.compose.ui.test.performTouchInput as originalTouch
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in screenshots of existing test interactions, absent from production APKs. */
private object ReleaseAuditCapture {
    val sequence = AtomicInteger()
    fun capture(action: String) {
        if (InstrumentationRegistry.getArguments().getString("folioAudit") != "true") return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "v2-interactions").apply { mkdirs() }
        Thread.sleep(180)
        val image = instrumentation.uiAutomation.takeScreenshot() ?: error("Audit screenshot unavailable")
        try {
            val caller = Thread.currentThread().stackTrace.firstOrNull { it.className.startsWith("dev.folio.scanner.") && it.className.endsWith("Test") }
            val label = caller?.let { it.className.substringAfterLast('.') + "-" + it.methodName } ?: "interaction"
            File(folder, "%05d-%s-%s.png".format(sequence.incrementAndGet(), label, action)).outputStream().use {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { image.recycle() }
    }
}

fun SemanticsNodeInteraction.performClick(): SemanticsNodeInteraction = originalClick().also { ReleaseAuditCapture.capture("click") }
fun SemanticsNodeInteraction.performScrollTo(): SemanticsNodeInteraction = originalScroll().also { ReleaseAuditCapture.capture("scroll") }
fun SemanticsNodeInteraction.performScrollToNode(matcher: SemanticsMatcher): SemanticsNodeInteraction = originalScrollToNode(matcher).also { ReleaseAuditCapture.capture("scroll-node") }
fun SemanticsNodeInteraction.performTouchInput(block: TouchInjectionScope.() -> Unit) = originalTouch(block).also { ReleaseAuditCapture.capture("gesture") }
