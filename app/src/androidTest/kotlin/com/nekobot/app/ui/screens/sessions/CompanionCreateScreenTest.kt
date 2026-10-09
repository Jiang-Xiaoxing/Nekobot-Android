package com.nekobot.app.ui.screens.sessions

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.data.local.*
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.model.CharacterPreset
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class CompanionCreateScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val character = CharacterPreset(id = "c", name = "姜晚星")
    private fun chineseContext() = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
    private fun text(id: Int) = chineseContext().getString(id)
    @Composable private fun TestTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
        val context = remember { chineseContext() }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
            NekobotTheme(darkTheme = darkTheme, content = content)
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Surface pixels can lag behind an already updated semantics tree by one frame.
        android.os.SystemClock.sleep(250)
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val file = File(compose.activity.getExternalFilesDir("validation"), "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Composable private fun content(state: CompanionCreateState, onPreview: (String) -> Unit = {}, onCreate: (String, String) -> Unit = {_,_->}, onTransfer: (String) -> Unit = {}, onOpen: (String) -> Unit = {}) {
        CompanionCreateContent(state, {}, {}, onPreview, onCreate, onTransfer, onOpen, {}, {}, {}, {}, {}, {})
    }

    @Test fun freshRoleKeepsSettingsCollapsedAndCreatesWithEditableGreeting() {
        var request: Pair<String, String>? = null
        compose.setContent { TestTheme(darkTheme = false) { content(CompanionCreateState(listOf(character), "c", CompanionEligibility.Available), onCreate = { name, greeting -> request = name to greeting }) } }
        compose.onNodeWithText(text(R.string.companion_optional_name)).assertDoesNotExist()
        compose.onNodeWithText("姜晚星").assertIsDisplayed()
        capture("companion-create-light")
        compose.onNodeWithText(text(R.string.companion_more_settings)).performClick()
        compose.onNodeWithText(text(R.string.companion_optional_name)).performScrollTo().performClick().performTextInput("晚星的新会话")
        compose.onNodeWithText(text(R.string.companion_optional_greeting)).performScrollTo().performClick().performTextInput("欢迎回来")
        compose.waitUntil(10000) { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
        capture("companion-create-keyboard")
        compose.onNodeWithTag("companion_primary").assertIsDisplayed().performClick()
        assertEquals("晚星的新会话" to "欢迎回来", request)
    }

    @Test fun usedRoleSelectsSessionAndExistingRoleOpensInsteadOfCreating() {
        var state by mutableStateOf(CompanionCreateState(listOf(character), "c", CompanionEligibility.Used(listOf(LocalSessionEntity("s", "我们的旧会话", characterId = "c", createdAt = "old", updatedAt = "old")))))
        var selected: String? = null
        compose.setContent { TestTheme(darkTheme = false) { content(state, onPreview = { selected = it }, onOpen = { selected = it }) } }
        compose.onNodeWithTag("companion_primary").performClick()
        compose.onNodeWithText("我们的旧会话").performScrollTo().performClick()
        assertEquals("s", selected); capture("companion-create-used")
        compose.runOnIdle { state = state.copy(eligibility = CompanionEligibility.Existing("owned")) }
        compose.onNodeWithText(text(R.string.companion_open_existing)).performClick()
        assertEquals("owned", selected)
    }

    @Test fun previewDarkNarrowLargeTextRemainsScrollableAndPrimaryStaysVisible() {
        val preview = CompanionTransferPreview("job", "名字很长的姜晚星（Agent）", "我们聊了很久的旧会话", 62000, 14, 764, 8,
            listOf("旧记忆来源未验证时会清楚标注，原会话保留。"), 30, emptyList(), 3, 100000000)
        var name: String? = null
        compose.setContent { TestTheme(darkTheme = true) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(340.dp).fillMaxHeight()) { content(CompanionCreateState(preview = preview), onTransfer = { name = it }) }
            }
        } }
        compose.onNodeWithTag("companion_primary").assertIsDisplayed()
        compose.onNodeWithTag("companion_content").performScrollToNode(hasText("旧记忆来源未验证时会清楚标注，原会话保留。"))
        capture("companion-preview-dark-large")
        compose.onNodeWithTag("companion_primary").performClick()
        assertEquals(preview.name, name)
    }

    @Test fun missingFilesDisableTransferAndRawErrorIsCollapsed() {
        val preview = CompanionTransferPreview("job", "晚星（Agent）", "旧会话", 2, 0, 1, 0, emptyList(), 0, listOf("附件无法读取：note.txt"), 0, 100)
        compose.setContent { TestTheme(darkTheme = false) { content(CompanionCreateState(preview = preview, error = "请先修复缺失附件", details = "SQLITE_INTERNAL_TEST_DETAIL")) } }
        compose.onNodeWithTag("companion_primary").assertIsNotEnabled()
        compose.onNodeWithText("SQLITE_INTERNAL_TEST_DETAIL").assertDoesNotExist()
        compose.onNodeWithTag("companion_content").performScrollToNode(hasText("请先修复缺失附件"))
        capture("companion-preview-failure")
        compose.onNodeWithText(text(R.string.companion_technical_details)).performScrollTo().performClick()
        compose.onNodeWithText("SQLITE_INTERNAL_TEST_DETAIL").assertExists()
    }

    @Test fun progressCanBeCancelledAndCompletedResultOpensTheSavedTarget() {
        val preview = CompanionTransferPreview("job", "晚星（Agent）", "旧会话", 62000, 0, 764, 0, emptyList(), 0, emptyList(), 0, 100)
        var state by mutableStateOf(CompanionCreateState(preview = preview, busy = true, progress = CompanionTransferProgress("正在复制原话", 20000, 62000)))
        var cancelled = false; var opened: String? = null
        compose.setContent { TestTheme(darkTheme = false) {
            CompanionCreateContent(state, {}, {}, {}, {_,_->}, {}, { opened = it }, { cancelled = true }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("companion_primary").assertIsNotEnabled()
        compose.onNodeWithTag("companion_content").performScrollToNode(hasText("正在复制原话 20000 / 62000"))
        capture("companion-transfer-progress")
        compose.onNodeWithText(text(R.string.common_cancel)).performScrollTo().performClick()
        assertTrue(cancelled)
        compose.runOnIdle { state = state.copy(busy = false, completed = "target", progress = null) }
        capture("companion-transfer-complete")
        compose.onNodeWithTag("companion_primary").performClick()
        assertEquals("target", opened)
    }
}
