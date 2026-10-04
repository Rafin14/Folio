package dev.folio.scanner

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.folio.scanner.backup.*
import dev.folio.scanner.data.*
import dev.folio.scanner.processing.ImagePipeline
import dev.folio.scanner.ui.*
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.junit.Assert.*

class BackupUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun optionalBackupRemainsDisconnectedAcrossActivityRecreationAndHasNoUploadAction() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Google Drive Backup").performScrollTo().performClick()
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithText("Sign in with Google").assertIsDisplayed()
        compose.onNodeWithText("Back Up Now").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        if(BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty()) {
            compose.onNodeWithText("Sign in with Google").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Google sign-in is not configured.",substring=true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Google sign-in is not configured.",substring=true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Back Up Now").assertDoesNotExist()
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("New document",useUnmergedTree=true).assertIsDisplayed()
    }
    @Test fun freshConnectedInstallationShowsLockedUploadsAndRestoreAction() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="fresh-ui-${UUID.randomUUID()}"
        val isolated=object:android.content.ContextWrapper(context) {
            override fun getSharedPreferences(key:String,mode:Int)=context.getSharedPreferences("$name-$key",mode)
        }
        val auth=object:DriveAuth {
            override suspend fun selectAccount(activity:android.content.Context)="fresh@example.invalid"
            override suspend fun connect(email:String)=DriveAuthorization(null)
            override fun complete(intent:android.content.Intent) {}
            override suspend fun token(email:String):String=error("No network access permitted")
            override suspend fun invalidate(token:String) {}
            override suspend fun signOut(email:String) {}
        }
        val db=Room.databaseBuilder(context,FolioDatabase::class.java,name).build()
        val pipeline=ImagePipeline(context)
        val repo=FolioBackupRepository(isolated,db,DocumentRepository(db,context,pipeline),auth,pipeline)
        try {
            runBlocking { repo.connected("fresh@example.invalid") }
            compose.runOnUiThread { compose.activity.setContent { FolioTheme("Dark") { BackupScreen(repo) {} } } }
            compose.onNodeWithContentDescription("Automatic backup").performScrollTo().assertIsOff().assertIsNotEnabled()
            compose.onNodeWithText("Back Up Now").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Find backup to restore").performScrollTo().assertIsEnabled()
            assertFalse(repo.state.value.backupUnlocked)
        } finally { pipeline.close(); db.close(); context.deleteDatabase(name) }
    }
    @Test fun connectedControlsRequireUploadConsentAndStayUsableInBothThemesAndLargeText() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="drive-ui-${UUID.randomUUID()}"
        val isolated=object:android.content.ContextWrapper(context) {
            override fun getSharedPreferences(key:String,mode:Int)=context.getSharedPreferences("$name-$key",mode)
        }
        val auth=object:DriveAuth {
            override suspend fun selectAccount(activity:android.content.Context)="ui@example.invalid"
            override suspend fun connect(email:String)=DriveAuthorization(null)
            override fun complete(intent:android.content.Intent) {}
            override suspend fun token(email:String):String=error("UI test must never access Google")
            override suspend fun invalidate(token:String) {}
            override suspend fun signOut(email:String) {}
        }
        val db=Room.databaseBuilder(context,FolioDatabase::class.java,name).build(); val pipeline=ImagePipeline(context)
        isolated.getSharedPreferences("drive-backup",0).edit().putLong("lastBackup",1).commit()
        val repo=FolioBackupRepository(isolated,db,DocumentRepository(db,context,pipeline),auth,pipeline)
        runBlocking { repo.connected("ui@example.invalid") }
        var dark by mutableStateOf(false); var large by mutableStateOf(false)
        compose.runOnUiThread { compose.activity.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,if(large) 1.6f else 1f)) {
                FolioTheme(if(dark) "Dark" else "Light") { BackupScreen(repo) {} }
            }
        } }
        try {
            for(mode in listOf(false,true)) for(font in listOf(false,true)) {
                compose.runOnUiThread { dark=mode; large=font }
                compose.onNodeWithText("Back Up Now").performScrollTo().performClick()
                compose.onNodeWithText("Back up your Folio library?").assertIsDisplayed()
                compose.onNodeWithText("Cancel").performClick()
                compose.onNodeWithContentDescription("Automatic backup").performScrollTo().performClick()
                compose.onNodeWithText("Enable automatic backup?").assertIsDisplayed()
                compose.onNodeWithText("Cancel").performClick(); assertFalse(repo.state.value.automatic)
                compose.onNodeWithText("Find backup to restore").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Disconnect Google Account").performScrollTo().performClick()
                compose.onNodeWithText("Disconnect Google Account?").assertIsDisplayed(); compose.onNodeWithText("Cancel").performClick()
            }
            compose.runOnUiThread { repo.state.value=repo.state.value.copy(status="Backing up…",running=true,done=2,total=4) }
            compose.onNodeWithText("2 of 4 steps").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Cancel and pause backup").performScrollTo().performClick()
            assertFalse(repo.state.value.automatic)
            for(mode in listOf(false,true)) {
                compose.runOnUiThread { dark=mode; large=true }
                compose.onNodeWithText("Delete All Cloud Backup").performScrollTo().performClick()
                compose.onNodeWithText("Delete all cloud backup?").assertIsDisplayed()
                compose.onNodeWithText("Cancel").performClick()
                assertNull(runBlocking { db.documents().backupRecord("ui@example.invalid:cloud-purge") })
                compose.onNodeWithText("Delete All Cloud Backup").performScrollTo().performClick()
                compose.onNodeWithText("Continue").performClick()
                for(value in listOf("delete","DELETE "," DELETE")) {
                    compose.onNodeWithText("Confirmation").performTextReplacement(value)
                    compose.onNodeWithText("Permanently Delete Cloud Backup").assertIsNotEnabled()
                }
                compose.onNodeWithText("Confirmation").performTextReplacement("DELETE")
                compose.onNodeWithText("Permanently Delete Cloud Backup").assertIsEnabled()
                compose.onNodeWithText("Confirmation").performImeAction()
                val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                java.io.File(context.getExternalFilesDir(null),"purge-confirm-${if(mode) "dark" else "light"}.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; screenshot.recycle()
                compose.onNodeWithText("Cancel").performClick()
                assertNull(runBlocking { db.documents().backupRecord("ui@example.invalid:cloud-purge") })
            }
            val local=runBlocking { DocumentRepository(db,context,pipeline).create("Local purge UI fixture") }
            compose.onNodeWithText("Delete All Cloud Backup").performScrollTo().performClick()
            compose.onNodeWithText("Continue").performClick()
            compose.onNodeWithText("Confirmation").performTextReplacement("DELETE")
            compose.onNodeWithText("Permanently Delete Cloud Backup").performClick()
            compose.waitUntil(10000) { runBlocking { db.documents().backupRecord("ui@example.invalid:cloud-purge") }!=null }
            compose.onNodeWithText("Cloud backup deletion is pending").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Back Up Now").performScrollTo().assertIsNotEnabled()
            assertNotNull(runBlocking { db.documents().document(local) })
            runBlocking { DocumentRepository(db,context,pipeline).purgeForTest(local) }
        } finally { isolated.getSharedPreferences("drive-backup",0).edit().clear().commit(); pipeline.close(); db.close(); context.deleteDatabase(name) }
    }
}
