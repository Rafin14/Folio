package dev.folio.scanner

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class AppearanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun systemThemeAndDynamicColorsSurviveRecreation() {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command:String) { ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() } }
        compose.onNodeWithContentDescription("Settings").performClick(); compose.onNodeWithText("System").performClick()
        try {
            for (dark in listOf(false,true)) {
                shell("cmd uimode night ${if(dark) "yes" else "no"}")
                compose.waitUntil(15000) { (compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)==dark }
                compose.activityRule.scenario.recreate(); compose.onNodeWithText("System").assertIsDisplayed()
                compose.waitForIdle()
                compose.runOnUiThread {
                    org.junit.Assert.assertEquals(!dark,androidx.core.view.WindowCompat.getInsetsController(compose.activity.window,compose.activity.window.decorView).isAppearanceLightStatusBars)
                }
                shell("screencap -p /sdcard/Download/folio-system-${if(dark) "dark" else "light"}.png")
            }
        } finally { shell("cmd uimode night no") }
    }
    @Test fun lightDarkAndLargeTextRemainNavigable() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) { ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() } }
        try {
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Dark").performClick()
            compose.waitForIdle()
            shell("screencap -p /sdcard/Download/folio-dark.png")
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Private by default").assertIsDisplayed()
            compose.onNodeWithText("Light").performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitForIdle()
            shell("screencap -p /sdcard/Download/folio-light.png")
            shell("settings put system font_scale 1.6")
            compose.waitUntil(15000) { compose.activity.resources.configuration.fontScale > 1.5f }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Search documents").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithText("New document", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
            compose.waitForIdle()
            shell("screencap -p /sdcard/Download/folio-large-text.png")
        } finally {
            shell("settings put system font_scale 1.0")
            compose.waitUntil(15000) { compose.activity.resources.configuration.fontScale < 1.1f }
        }
    }
}
