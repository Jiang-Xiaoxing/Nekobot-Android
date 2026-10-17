package com.nekobot.app.ui.screens.settings

import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.data.local.PrefsManager
import com.nekobot.app.data.local.db.NekobotDatabase
import com.nekobot.app.ui.theme.NekobotTheme
import java.io.File
import java.util.Locale
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Isolated emulator and synthetic settings only. Server actions are callbacks, with no API calls. */
class SettingsHomeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var restore: (() -> Unit)? = null

    @Before fun isolate() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        val prefs = ServiceContainer.prefs
        val previousProfile = prefs.activeDbName
        val previousMode = prefs.appMode
        val previousLanguage = prefs.language
        val previousLock = prefs.appLockEnabled
        val previousScale = prefs.fontScale
        val previousFollow = prefs.followSystemFontScale
        val profile = "settings_home_${UUID.randomUUID().toString().replace("-", "")}"
        restore = {
            prefs.appLockEnabled = previousLock
            prefs.language = previousLanguage
            prefs.fontScale = previousScale
            prefs.followSystemFontScale = previousFollow
            ServiceContainer.switchLocalDb(previousProfile)
            ServiceContainer.switchAppMode(previousMode)
            prefs.removeDbProfile(profile)
            NekobotDatabase.deleteProfileFile(compose.activity, profile)
        }
        ServiceContainer.switchAppMode(AppMode.LOCAL)
        ServiceContainer.switchLocalDb(profile)
        prefs.language = PrefsManager.LANGUAGE_ZH
        prefs.appLockEnabled = false
        prefs.fontScale = 1f
        prefs.followSystemFontScale = false
    }

    @After fun cleanup() { restore?.invoke() }

    @Composable private fun Theme(dark: Boolean = false, content: @Composable () -> Unit) {
        val context = remember {
            compose.activity.createConfigurationContext(
                Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
            )
        }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
            NekobotTheme(darkTheme = dark, content = content)
        }
    }

    @Composable private fun Home(
        mode: AppMode,
        onNavigate: (String) -> Unit = {},
        lockAvailable: Boolean = true,
        onLock: (Boolean) -> Unit = {}
    ) {
        var latest by remember { mutableStateOf(false) }
        var archived by remember { mutableStateOf(true) }
        var optimize by remember { mutableStateOf(false) }
        SettingsHomeContent(
            scrollState = rememberScrollState(),
            appMode = mode,
            language = "简体中文",
            version = "synthetic",
            openLatestSessionOnLaunch = latest,
            recentSessionsIncludeArchived = archived,
            appLockEnabled = false,
            appLockAvailable = lockAvailable,
            imagePromptOptimizeEnabled = optimize,
            onOpenLatestSessionChange = { latest = it },
            onIncludeArchivedChange = { archived = it },
            onAppLockChange = onLock,
            onImagePromptOptimizeChange = { optimize = it },
            onLanguageClick = {},
            onConnectionClick = {},
            onNavigate = onNavigate
        )
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = compose.onNodeWithTag("settings-test-root").captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, "settings-home-$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    @Test fun localAndServerModesKeepEveryApplicableHomeDestination() {
        val visited = mutableListOf<String>()
        var mode by mutableStateOf(AppMode.LOCAL)
        compose.setContent { Theme { Box(Modifier.testTag("settings-test-root").background(MaterialTheme.colorScheme.background)) { Home(mode, visited::add) } } }
        val common = listOf("data_portability", "webdav_backup", "data_maintenance", "agent_settings", "logs", "diagnostic_center", "about")
        (listOf("local_ai_models") + common).forEach { route ->
            compose.onNodeWithTag("settings-home-$route").performScrollTo().performClick()
        }
        assertEquals(listOf("local_ai_models") + common, visited)
        compose.onNodeWithTag("settings-home-feature_switches").assertDoesNotExist()
        compose.onNodeWithTag("settings-home-system_settings").assertDoesNotExist()
        compose.onNodeWithTag("settings-home-config_transfer").assertDoesNotExist()
        compose.runOnIdle { mode = AppMode.SERVER; visited.clear() }
        (common + listOf("feature_switches", "system_settings", "config_transfer")).forEach { route ->
            compose.onNodeWithTag("settings-home-$route").performScrollTo().performClick()
        }
        assertEquals(common + listOf("feature_switches", "system_settings", "config_transfer"), visited)
        compose.onNodeWithTag("settings-home-local_ai_models").assertDoesNotExist()
        compose.onNodeWithTag("settings-home-image-prompt").assertDoesNotExist()
        // The index does not expose the expert form or operations inline.
        compose.onNodeWithTag("settings-connection-server-url").assertDoesNotExist()
        compose.onNodeWithTag("settings-connection-reload").assertDoesNotExist()
    }

    @Test fun largeTextAndSmallWidthKeepNavigationAndWholeRowSwitchesReachable() {
        ServiceContainer.prefs.fontScale = 1.6f
        compose.setContent {
            Theme(dark = true) {
                Box(Modifier.requiredSize(320.dp, 560.dp).testTag("settings-test-root").background(MaterialTheme.colorScheme.background)) { Home(AppMode.LOCAL) }
            }
        }
        compose.onNodeWithTag("settings-home-open-latest").performScrollTo()
            .assertHeightIsAtLeast(48.dp).assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("settings-home-include-archived").performScrollTo().assertIsOn()
            .performClick().assertIsOff()
        screenshot("large-dark-common")
        compose.onNodeWithTag("settings-home-image-prompt").performScrollTo().assertIsOff()
            .performClick().assertIsOn()
        compose.onNodeWithTag("settings-home-data_portability").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        screenshot("large-dark-data")
        compose.onNodeWithTag("settings-home-about").performScrollTo().assertIsDisplayed()
        screenshot("large-dark-support")
    }

    @Test fun pendingFingerprintAuthenticationDisablesTheSingleSwitchTarget() {
        var calls = 0
        compose.setContent { Theme { Home(AppMode.LOCAL, lockAvailable = false, onLock = { calls++ }) } }
        compose.onNodeWithTag("settings-home-app-lock").performScrollTo().assertIsNotEnabled()
            .performTouchInput { click() }
        assertEquals(0, calls)
    }

    @Test fun connectionPageKeepsServerFormActionsAndModeChoiceWithSyntheticCallbacks() {
        var mode by mutableStateOf(AppMode.SERVER)
        var url by mutableStateOf("http://synthetic.invalid")
        var saves = 0
        var reloads = 0
        var logouts = 0
        ServiceContainer.prefs.fontScale = 1.6f
        compose.setContent {
            Theme {
                Box(Modifier.requiredSize(320.dp, 560.dp).testTag("settings-test-root").background(MaterialTheme.colorScheme.background)) {
                    SettingsConnectionContent(
                        rememberScrollState(), mode, url, "合成测试账号", false,
                        { mode = it }, { url = it }, { saves++ }, { reloads++ }, { logouts++ }
                    )
                }
            }
        }
        compose.onNodeWithTag("settings-connection-server-url").performScrollTo()
            .performTextReplacement("https://synthetic.invalid")
        compose.onNodeWithTag("settings-connection-save-url").performScrollTo().performClick()
        assertEquals(1, saves)
        assertEquals("https://synthetic.invalid", url)
        screenshot("large-server-form")
        compose.onNodeWithTag("settings-connection-reload").performScrollTo().performClick()
        compose.onNodeWithTag("settings-connection-logout").performScrollTo().performClick()
        assertEquals(1, reloads)
        assertEquals(1, logouts)
        screenshot("large-server-actions")
        compose.onNodeWithTag("settings-connection-mode").performScrollTo().assertIsOff().performClick().assertIsOn()
        assertEquals(AppMode.LOCAL, mode)
        compose.onNodeWithTag("settings-connection-server-url").assertDoesNotExist()
        compose.onNodeWithTag("settings-connection-logout").assertDoesNotExist()
    }

    @Test fun connectionBackAndDestinationBackRestoreHomeScrollAndAllowReentry() {
        val visited = mutableListOf<String>()
        compose.setContent {
            Theme {
                Box(Modifier.testTag("settings-test-root").background(MaterialTheme.colorScheme.background)) {
                    val nav = rememberNavController()
                    NavHost(nav, "settings") {
                        composable("settings") {
                            SettingsScreen({}, { visited += it; nav.navigate("test/$it") }, { nav.popBackStack() })
                        }
                        composable("test/{route}") {
                            Column {
                                Text("合成测试目的页")
                                Button(onClick = { nav.popBackStack() }, modifier = Modifier.testTag("settings-test-return")) { Text("返回") }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("settings-home-connection").performScrollTo()
        fun homeScroll() = compose.onNodeWithTag("settings-home-scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = homeScroll()
        assertTrue(before > 0f)
        compose.onNodeWithTag("settings-home-connection").performClick()
        compose.onNodeWithTag("settings-connection-mode").assertIsDisplayed()
        screenshot("local-connection")
        compose.onNodeWithTag("settings-home-back").performClick()
        assertEquals(before, homeScroll(), 1f)
        compose.onNodeWithTag("settings-home-connection").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("settings-home-connection").assertIsDisplayed()
        assertEquals(before, homeScroll(), 1f)
        compose.onNodeWithTag("settings-home-agent_settings").performScrollTo().performTouchInput {
            click()
            advanceEventTime(1)
            click()
        }
        compose.onNodeWithTag("settings-test-return").assertIsDisplayed()
        assertEquals(listOf("agent_settings"), visited)
        compose.onNodeWithTag("settings-test-return").performClick()
        compose.onNodeWithTag("settings-home-agent_settings").assertIsDisplayed().performClick()
        assertEquals(listOf("agent_settings", "agent_settings"), visited)
    }
}
