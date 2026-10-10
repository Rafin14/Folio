package dev.folio.scanner

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.folio.scanner.backup.*
import dev.folio.scanner.data.*
import dev.folio.scanner.processing.ImagePipeline
import dev.folio.scanner.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class BackupInspectionUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun grantedInspectionReplacesActionAndDoesNotReconnectAndRevocationIsRechecked() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="inspection-${UUID.randomUUID()}"
        val isolated=object:android.content.ContextWrapper(context) { override fun getSharedPreferences(key:String,mode:Int)=context.getSharedPreferences("$name-$key",mode) }
        var granted=false; var connects=0
        val auth=object:DriveAuth {
            override suspend fun selectAccount(activity:android.content.Context)="inspection@example.invalid"
            override suspend fun connect(email:String):DriveAuthorization { connects++; return DriveAuthorization(null) }
            override suspend fun inspectForPurge(email:String):DriveAuthorization { granted=true; return DriveAuthorization(null) }
            override suspend fun inspectionAuthorized(email:String)=granted
            override fun complete(intent:android.content.Intent) {}
            override suspend fun token(email:String):String=error("No network in fixture")
            override suspend fun invalidate(token:String) {}
            override suspend fun signOut(email:String) {}
        }
        val db=Room.databaseBuilder(context,FolioDatabase::class.java,name).build(); val pipeline=ImagePipeline(context)
        val repository=FolioBackupRepository(isolated,db,DocumentRepository(db,context,pipeline),auth,pipeline)
        try {
            runBlocking { repository.connected("inspection@example.invalid") }
            compose.runOnUiThread { compose.activity.setContent { FolioTheme("Dark") { BackupScreen(repository) {} } } }
            compose.waitUntil(10000) { repository.inspectionAuthorized.value==false }
            compose.onNodeWithText("Authorize safe folder inspection").performScrollTo().performClick()
            compose.waitUntil(10000) { repository.inspectionAuthorized.value==true }
            compose.onNodeWithText("Folder Inspection Authorized").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Authorize safe folder inspection").assertDoesNotExist()
            assertEquals(0,connects)
            assertTrue(isolated.getSharedPreferences("drive-backup",0).getBoolean("inspection:inspection@example.invalid",false))
            granted=false; runBlocking { repository.refreshInspectionAuthorization() }
            compose.onNodeWithText("Authorize safe folder inspection").performScrollTo().assertIsDisplayed()
            assertFalse(isolated.getSharedPreferences("drive-backup",0).getBoolean("inspection:inspection@example.invalid",true))
        } finally { pipeline.close(); db.close(); context.deleteDatabase(name) }
    }
}
