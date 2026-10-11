package com.nekobot.app.ui.screens.sessions

import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nekobot.app.data.local.RelationshipBackground
import com.nekobot.app.ui.theme.NekobotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/** Runs the production components with synthetic values; no user database or model is used. */
class SessionJournalLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable private fun Theme(content: @Composable () -> Unit) {
        val context = remember { compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }) }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
            NekobotTheme(darkTheme = false, content = content)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Compose's semantic tree can be ready one compositor frame before the screenshot.
        android.os.SystemClock.sleep(400)
        compose.waitForIdle()
        val automation = instrumentation.uiAutomation
        requireNotNull(automation.takeScreenshot()).let { bitmap ->
            File(compose.activity.getExternalFilesDir("validation"), "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun sevenSkinsRenderAndFeelingTabsKeepTheirContent() {
        var scene by mutableStateOf(RelationshipBackground.COAST)
        val values = listOf(JournalFeeling("心情", "轻松随和，带有一点好奇", {}),
            JournalFeeling("情绪强度", "平和而自然，带着被逗乐的笑意", {}), JournalFeeling("精力", "尚无依据", {}))
        compose.setContent { Theme { RelationshipSkinTheme(scene) {
            RelationshipJournalBackdrop(scene) {
                Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("关系状态 · 林小满", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineSmall)
                    RelationshipPaper {
                        JournalHeading("上次感受")
                        RelationshipFeelingDeck("synthetic", values)
                    }
                    JournalAction("评估设置", "模型与更新频率", Icons.Filled.Tune, {})
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        JournalAction("变化依据", icon = Icons.Filled.History, onClick = {}, modifier = Modifier.weight(1f))
                        JournalAction("旧版记录", icon = Icons.Filled.Archive, onClick = {}, modifier = Modifier.weight(1f))
                    }
                }
            }
        } } }
        RelationshipBackground.entries.forEach { background ->
            compose.runOnIdle { scene = background }
            compose.onNodeWithTag("relationship_feeling_0").assertIsDisplayed()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("relationship_scene_${background.id}_ready").fetchSemanticsNodes().isNotEmpty()
            }
            capture("skin-${background.id}-native")
        }
        compose.onNodeWithTag("relationship_feeling_1").performClick()
        compose.onNodeWithText("平和而自然，带着被逗乐的笑意").assertIsDisplayed()
    }
}
