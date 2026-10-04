package dev.folio.scanner

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import org.junit.Assert.*
import org.junit.Test

/** Run prepare, stop/reboot the app/device, then verify in a separate instrumentation process. */
class PdfFolderPersistenceTest {
    @Test fun prepareGrantForRestartAndReboot() {
        val inst=InstrumentationRegistry.getInstrumentation(); val context=inst.targetContext
        inst.uiAutomation.executeShellCommand("am start -W -n dev.folio.scanner.test/dev.folio.scanner.StorageGrantActivity").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        assertTrue(utility.rememberDestination("restart-check",DocumentsContract.buildTreeDocumentUri("dev.folio.scanner.test.storage","root")))
    }
    @Test fun verifyPersistedGrantCanWriteAfterRestartAndReboot() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        org.junit.Assume.assumeTrue(context.getSharedPreferences("pdf-utility-destinations",0).contains("restart-check")) // Host must prepare the separate-process fixture.
        val tree=utility.rememberedDestination("restart-check")
        assertNotNull("Prepared persistent grant survives restart/reboot",tree)
        val parent=DocumentsContract.buildDocumentUriUsingTree(tree!!,DocumentsContract.getTreeDocumentId(tree))
        val file=DocumentsContract.createDocument(context.contentResolver,parent,"text/plain","permission-check.txt")!!
        try { context.contentResolver.openOutputStream(file,"w")!!.use { it.write("persistent SAF permission".toByteArray()) }; assertEquals("persistent SAF permission",context.contentResolver.openInputStream(file)!!.bufferedReader().use { it.readText() }) }
        finally { DocumentsContract.deleteDocument(context.contentResolver,file) }
    }
}
