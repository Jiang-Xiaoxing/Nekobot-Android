package com.nekobot.app.ui.screens.memory

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.CompanionMemoryStore
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class FusedCompanionMemoryScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun memoryPageShowsSeparateControlsAndSavesManualRecord() {
        val id="fused-ui-${UUID.randomUUID()}"
        val db=NekobotDatabase.get(compose.activity,ServiceContainer.prefs.activeDbName)
        val session=LocalSessionEntity(id,"Synthetic UI",characterId="$id-character",sessionMode="agent",
            inheritCharacter=true,longConversationEnabled=true,companionMode=true,createdAt="now",updatedAt="now")
        try {
            runBlocking { db.sessionDao().upsert(session); CompanionMemoryStore(db).ensureScope(session) }
            compose.setContent { MaterialTheme { FusedCompanionMemoryScreen(id,{}, {}) } }
            compose.onNodeWithText("角色记忆 · 长久陪伴").assertIsDisplayed()
            compose.onNodeWithContentDescription("新增记忆").performClick()
            compose.onNodeWithText("记忆正文（最多 1500 字符）").performTextInput("难过时希望先被倾听")
            compose.onNodeWithText("两个选项分别控制提供频率和修改权限，互不影响。").performScrollTo().assertIsDisplayed()
            compose.onAllNodes(isToggleable()).assertCountEquals(2)
            compose.onAllNodes(isToggleable())[0].performClick()
            compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("编辑角色记忆").fetchSemanticsNodes().isEmpty() }
            runBlocking {
                val store=CompanionMemoryStore(db); val fact=store.validatedMemories(store.ensureScope(session)).single()
                assertTrue(fact.alwaysProvide); assertFalse(fact.locked); assertTrue(fact.manual)
            }
            compose.onNodeWithTag("fused-memory-list").performScrollToNode(hasText("版本"))
            compose.onNodeWithText("版本").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("记忆版本").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("记忆版本").assertIsDisplayed()
        } finally { runBlocking { db.sessionDao().deleteById(id) } }
    }
}
