package com.nekobot.app.ui.screens.more

import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nekobot.app.data.local.AppMode
import com.nekobot.app.ui.navigation.Routes
import com.nekobot.app.ui.theme.BgDark
import com.nekobot.app.ui.theme.BgLight
import com.nekobot.app.ui.theme.BgSurface
import com.nekobot.app.ui.theme.BgSurfaceLight
import com.nekobot.app.ui.theme.BgSurfaceVariant
import com.nekobot.app.ui.theme.BgSurfaceVariantLight
import com.nekobot.app.ui.theme.OnSurface
import com.nekobot.app.ui.theme.OnSurfaceLight
import com.nekobot.app.ui.theme.OnSurfaceVariant
import com.nekobot.app.ui.theme.OnSurfaceVariantLight
import com.nekobot.app.ui.theme.Primary
import com.nekobot.app.ui.theme.PrimaryLight
import com.nekobot.app.ui.theme.buildTypography
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Synthetic display parameters and callbacks only; no preferences, database or API writes. */
class MoreHomeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val commonRoutes = listOf(
        Routes.AI_CONFIG_CENTER, Routes.GLOBAL_SEARCH, Routes.SETTINGS,
        Routes.MEMORY, Routes.AGENT_MEMORY, Routes.STATE_HISTORY,
        Routes.STYLE_SETTINGS, Routes.RELATIONSHIP_BACKGROUND, Routes.STICKERS,
        Routes.EXTENSIONS, Routes.SYSTEM_OPERATIONS
    )
    private val localRoutes = commonRoutes.toMutableList().apply {
        add(indexOf(Routes.AGENT_MEMORY), Routes.AGENT_CHARACTER_MEMORY)
        add(Routes.DB_PROFILE)
    }

    @Before fun emulatorOnly() {
        assumeTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
    }

    @Composable private fun Theme(
        scale: Float = 1f,
        dark: Boolean = false,
        content: @Composable () -> Unit
    ) {
        val context = remember {
            compose.activity.createConfigurationContext(
                Configuration(compose.activity.resources.configuration).apply {
                    setLocale(Locale.SIMPLIFIED_CHINESE)
                }
            )
        }
        val colors = if (dark) darkColorScheme(
            primary = Primary, background = BgDark, surface = BgSurface,
            surfaceVariant = BgSurfaceVariant, onSurface = OnSurface,
            onBackground = OnSurface, onSurfaceVariant = OnSurfaceVariant
        ) else lightColorScheme(
            primary = PrimaryLight, background = BgLight, surface = BgSurfaceLight,
            surfaceVariant = BgSurfaceVariantLight, onSurface = OnSurfaceLight,
            onBackground = OnSurfaceLight, onSurfaceVariant = OnSurfaceVariantLight
        )
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides context.resources.configuration,
            LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f)
        ) {
            MaterialTheme(
                colorScheme = colors,
                typography = buildTypography(fontFamily = "default", fontScale = scale),
                content = content
            )
        }
    }

    @Composable private fun Home(
        mode: AppMode,
        onNavigate: (String) -> Unit = {},
        onLogout: () -> Unit = {}
    ) {
        MoreHomeContent(mode, "synthetic-user", onNavigate, onLogout)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = compose.onNodeWithTag("more-test-root").captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, "more-home-$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    @Test fun localAndServerModesKeepEveryApplicableDestination() {
        val visited = mutableListOf<String>()
        var mode by mutableStateOf(AppMode.LOCAL)
        var logouts = 0
        compose.setContent {
            Theme {
                Box(Modifier.testTag("more-test-root")) {
                    Home(mode, visited::add, { logouts++ })
                }
            }
        }
        localRoutes.forEach { route ->
            compose.onNodeWithTag("more-home-$route").performScrollTo()
                .assertIsDisplayed().performClick()
        }
        assertEquals(localRoutes, visited)
        compose.onNodeWithTag("more-home-logout").assertDoesNotExist()

        compose.runOnIdle { mode = AppMode.SERVER; visited.clear() }
        commonRoutes.forEach { route ->
            compose.onNodeWithTag("more-home-$route").performScrollTo()
                .assertIsDisplayed().performClick()
        }
        assertEquals(commonRoutes, visited)
        compose.onNodeWithTag("more-home-${Routes.AGENT_CHARACTER_MEMORY}").assertDoesNotExist()
        compose.onNodeWithTag("more-home-${Routes.DB_PROFILE}").assertDoesNotExist()
        compose.onNodeWithTag("more-home-logout").performScrollTo().performClick()
        assertEquals(1, logouts)
        screenshot("normal-server-management")
    }

    @Test fun commonDestinationsAppearBeforeMemoryAndAppearance() {
        compose.setContent {
            Theme {
                Box(Modifier.testTag("more-test-root")) { Home(AppMode.LOCAL) }
            }
        }
        val ai = compose.onNodeWithTag("more-home-${Routes.AI_CONFIG_CENTER}")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val search = compose.onNodeWithTag("more-home-${Routes.GLOBAL_SEARCH}")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val settings = compose.onNodeWithTag("more-home-${Routes.SETTINGS}")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val memory = compose.onNodeWithTag("more-home-${Routes.MEMORY}").getUnclippedBoundsInRoot()
        assertTrue(ai.top < search.top && search.top < settings.top && settings.top < memory.top)
        screenshot("normal-common")
        compose.onNodeWithTag("more-home-${Routes.AGENT_MEMORY}").performScrollTo()
        screenshot("normal-memory")
        compose.onNodeWithTag("more-home-${Routes.DB_PROFILE}").performScrollTo()
        screenshot("normal-management")
    }

    @Test fun smallScreenLargeTextKeepsAllRowsVisibleAfterScrolling() {
        val visited = mutableListOf<String>()
        compose.setContent {
            Theme(scale = 1.6f, dark = true) {
                Box(
                    Modifier.sizeIn(maxWidth = 320.dp, maxHeight = 560.dp)
                        .fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                        .testTag("more-test-root").background(MaterialTheme.colorScheme.background)
                ) { Home(AppMode.LOCAL, visited::add) }
            }
        }
        val root = compose.onNodeWithTag("more-test-root").getUnclippedBoundsInRoot()
        localRoutes.forEach { route ->
            val row = compose.onNodeWithTag("more-home-$route").performScrollTo()
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val bounds = row.getUnclippedBoundsInRoot()
            assertTrue("$route is clipped", bounds.top >= root.top && bounds.bottom <= root.bottom)
            row.performClick()
            if (route == Routes.SETTINGS) screenshot("large-dark-common")
            if (route == Routes.AGENT_MEMORY) screenshot("large-dark-memory")
        }
        assertEquals(localRoutes, visited)
        screenshot("large-dark-management")
    }

    @Test fun destinationBackRestoresScrollAndAllowsRepeatedEntry() {
        val visited = mutableListOf<String>()
        compose.setContent {
            Theme {
                Box(Modifier.testTag("more-test-root")) {
                    val nav = rememberNavController()
                    NavHost(nav, "synthetic-more") {
                        composable("synthetic-more") {
                            Home(AppMode.LOCAL, onNavigate = {
                                visited += it
                                nav.navigate("synthetic-destination")
                            })
                        }
                        composable("synthetic-destination") {
                            Column {
                                Text("Synthetic destination")
                                Button(
                                    onClick = { nav.popBackStack() },
                                    modifier = Modifier.testTag("more-test-return")
                                ) { Text("Back") }
                            }
                        }
                    }
                }
            }
        }
        fun homeScroll() = compose.onNodeWithTag("more-home-scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithTag("more-home-${Routes.DB_PROFILE}").performScrollTo()
        val before = homeScroll()
        assertTrue(before > 0f)
        compose.onNodeWithTag("more-home-${Routes.DB_PROFILE}").performClick()
        compose.onNodeWithTag("more-test-return").assertIsDisplayed().performClick()
        compose.onNodeWithTag("more-home-${Routes.DB_PROFILE}").assertIsDisplayed()
        assertEquals(before, homeScroll(), 1f)
        compose.onNodeWithTag("more-home-${Routes.DB_PROFILE}").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("more-home-${Routes.DB_PROFILE}").assertIsDisplayed()
        assertEquals(listOf(Routes.DB_PROFILE, Routes.DB_PROFILE), visited)
        assertEquals(before, homeScroll(), 1f)
    }

    @Test fun rapidRepeatedNavigationWaitsForThePageToResume() {
        val visited = mutableListOf<String>()
        lateinit var owner: SyntheticLifecycleOwner
        compose.runOnUiThread {
            owner = SyntheticLifecycleOwner().apply {
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
        }
        compose.setContent {
            Theme {
                Home(AppMode.LOCAL, rememberMoreNavigateOnce(visited::add, owner))
            }
        }
        compose.onNodeWithTag("more-home-${Routes.SETTINGS}").performTouchInput {
            click()
            advanceEventTime(1)
            click()
        }
        assertEquals(listOf(Routes.SETTINGS), visited)
        compose.runOnIdle { owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        compose.onNodeWithTag("more-home-${Routes.SETTINGS}").performClick()
        assertEquals(listOf(Routes.SETTINGS), visited)
        compose.runOnIdle { owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        compose.onNodeWithTag("more-home-${Routes.SETTINGS}").performClick()
        assertEquals(listOf(Routes.SETTINGS, Routes.SETTINGS), visited)
        compose.runOnIdle { owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
    }

    private class SyntheticLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
}
