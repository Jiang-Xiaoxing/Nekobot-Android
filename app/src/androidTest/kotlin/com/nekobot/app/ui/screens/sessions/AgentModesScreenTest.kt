package com.nekobot.app.ui.screens.sessions

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class AgentModesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun context() = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
    private fun text(id: Int) = context().getString(id)
    @Composable private fun Theme(content: @Composable () -> Unit) {
        val context = remember { context() }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
            NekobotTheme(darkTheme = false) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.statusBarsPadding().navigationBarsPadding()) { content() }
                }
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(250)
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            File(compose.activity.getExternalFilesDir("validation"), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun legacyDetailsExposeConversionWithoutOldSwitches() {
        var opened = false
        compose.setContent { Theme { Box(Modifier.padding(16.dp)) {
            AgentModeDetailsCard(false, true, { fail("Wrong route") }, { opened = true })
        } } }
        compose.onNodeWithText(text(R.string.agent_legacy_title)).assertIsDisplayed()
        compose.onAllNodes(hasText("继承完整角色能力", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("长久陪伴：", substring = true)).assertCountEquals(0)
        capture("agent-legacy-details")
        compose.onNodeWithText(text(R.string.agent_character_entry)).performClick()
        assertTrue(opened)
    }
    @Test fun independentDetailsOpenOwnMemories() {
        var opened = false
        compose.setContent { Theme { Box(Modifier.padding(16.dp)) {
            AgentModeDetailsCard(true, true, { opened = true }, { fail("Wrong route") })
        } } }
        compose.onNodeWithText(text(R.string.agent_legacy_title)).assertDoesNotExist()
        capture("agent-character-details")
        compose.onNodeWithText(text(R.string.agent_character_memories)).performClick()
        assertTrue(opened)
    }
    @Test fun narrowLargeFontFiltersScrollAndSelectAgentCharacterSeparately() {
        var selected by mutableStateOf(SessionFilter.ALL)
        compose.setContent { Theme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(320.dp)) {
                    SessionStatFilters(SessionOverview(characterSessions = 2, agentSessions = 3, agentCharacterSessions = 7, archived = 4), selected, { selected = it })
                }
            }
        } }
        compose.onNodeWithText(text(R.string.sessions_filter_agent_characters)).performScrollTo().performClick()
        assertEquals(SessionFilter.AGENT_CHARACTERS, selected)
        capture("agent-character-filter-large-text")
        compose.onNodeWithText(text(R.string.sessions_archive)).performScrollTo().performClick()
        assertEquals(SessionFilter.ARCHIVED, selected)
        compose.onNodeWithText(text(R.string.sessions_filter_agent_sessions)).performScrollTo().performClick()
        assertEquals(SessionFilter.AGENT_SESSIONS, selected)
    }
}
