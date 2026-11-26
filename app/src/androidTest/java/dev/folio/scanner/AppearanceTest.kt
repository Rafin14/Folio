package dev.folio.scanner

import android.os.ParcelFileDescriptor
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

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
            compose.onNodeWithText("Private by default").performScrollTo().assertIsDisplayed()
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

    @Test fun glassStrengthPersistsWhenEffectsAreToggled() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val prefs = context.getSharedPreferences("appearance", 0)
        val initial = prefs.all
        val device = UiDevice.getInstance(inst)
        fun settings() = compose.onNodeWithContentDescription("Settings").performClick()
        fun capture(name: String) {
            compose.waitForIdle()
            Thread.sleep(400)
            assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null), "folio-glass-$name.png")))
        }
        try {
            prefs.edit().putBoolean("glassBlurEnabled", true).putInt("glassBlurStrength", 100)
                .putInt("glassTranslucency", 50).commit()
            compose.activityRule.scenario.recreate()
            settings()
            val toggle = compose.onNodeWithContentDescription("Background blur")
            val dial = compose.onNodeWithContentDescription("Blur strength")
            val translucency = compose.onNodeWithContentDescription("Translucency")
            dial.performScrollTo().assertIsEnabled()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(130f) }
            compose.onNodeWithText("130%").assertIsDisplayed()
            capture("enabled")
            toggle.performScrollTo().performClick()
            dial.performScrollTo().assertIsNotEnabled()
            translucency.performScrollTo().assertIsEnabled()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(75f) }
            compose.onNodeWithText("75%").assertIsDisplayed()
            capture("disabled")
            compose.activityRule.scenario.recreate()
            dial.performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("130%").assertIsDisplayed()
            translucency.performScrollTo().assertIsEnabled()
            compose.onNodeWithText("75%").assertIsDisplayed()
            toggle.performScrollTo().performClick()
            dial.assertIsEnabled()
            compose.onNodeWithText("130%").assertIsDisplayed()
        } finally {
            prefs.edit().apply {
                (initial["glassBlurEnabled"] as? Boolean)?.let { putBoolean("glassBlurEnabled", it) } ?: remove("glassBlurEnabled")
                (initial["glassBlurStrength"] as? Int)?.let { putInt("glassBlurStrength", it) } ?: remove("glassBlurStrength")
                (initial["glassTranslucency"] as? Int)?.let { putInt("glassTranslucency", it) } ?: remove("glassTranslucency")
            }.commit()
        }
    }
}
