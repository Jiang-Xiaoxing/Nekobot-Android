package com.nekobot.app.ui.screens.memory

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.nekobot.app.ServiceContainer
import com.nekobot.app.R
import com.nekobot.app.data.local.ai.CompanionMemoryStore
import com.nekobot.app.data.local.db.LocalSessionEntity
import com.nekobot.app.data.local.db.LocalMessageEntity
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
            compose.onNodeWithText(compose.activity.getString(R.string.agent_character_memory_title)).assertIsDisplayed()
            compose.waitUntil(5000) { compose.onAllNodes(hasContentDescription("新增记忆") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("新增记忆").performClick()
            compose.onNodeWithText("记忆正文（最多 1500 字符）").performTextInput("难过时希望先被倾听")
            // A database refresh from ongoing chat must not discard a user's unsaved edit.
            runBlocking { db.messageDao().upsert(LocalMessageEntity("$id-message",id,"user","后台消息",timestamp="now",createdAt="now")) }
            compose.waitForIdle()
            compose.onNodeWithText(compose.activity.getString(R.string.agent_character_memory_edit)).assertIsDisplayed()
            compose.onNodeWithText("两个选项分别控制提供频率和修改权限，互不影响。").performScrollTo().assertIsDisplayed()
            compose.onAllNodes(isToggleable()).assertCountEquals(2)
            compose.onAllNodes(isToggleable())[0].performClick()
            compose.onNodeWithText("保存",useUnmergedTree=true).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText(compose.activity.getString(R.string.agent_character_memory_edit)).fetchSemanticsNodes().isEmpty() }
            runBlocking {
                val store=CompanionMemoryStore(db); val fact=store.validatedMemories(store.ensureScope(session)).single()
                assertTrue(fact.alwaysProvide); assertFalse(fact.locked); assertTrue(fact.manual)
            }
            // The database save completes before the asynchronous list refresh is drawn.
            compose.waitUntil(5000) { compose.onAllNodesWithText("难过时希望先被倾听").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("fused-memory-list").performScrollToNode(hasText("版本"))
            compose.onNodeWithText("版本").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("记忆版本").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("记忆版本").assertIsDisplayed()
        } finally { runBlocking { db.sessionDao().deleteById(id) } }
    }

    @Test fun importedUnverifiedMemorySourceIsNotLabelledAsManualEntry() {
        val id = "imported-ui-${UUID.randomUUID()}"
        val db = NekobotDatabase.get(compose.activity, ServiceContainer.prefs.activeDbName)
        val session = LocalSessionEntity(id, "来源说明测试", characterId = "$id-character", sessionMode = "agent",
            inheritCharacter = true, longConversationEnabled = true, companionMode = true, createdAt = "now", updatedAt = "now")
        try {
            runBlocking {
                db.sessionDao().upsert(session)
                val scope = CompanionMemoryStore(db).ensureScope(session)
                db.companionMemoryDao().saveMemory(com.nekobot.app.data.local.db.CompanionMemoryEntity(
                    "$id-memory", scope.id, "tea", 1, "fact", "玩家", "喜欢茉莉茶", "active",
                    provenance = "imported_unverified", createdAt = "old", updatedAt = "old"))
            }
            compose.setContent { MaterialTheme { FusedCompanionMemoryScreen(id, {}, {}) } }
            compose.waitUntil(5000) { compose.onAllNodesWithText("喜欢茉莉茶").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("fused-memory-list").performScrollToNode(hasText("查看原话"))
            compose.onNodeWithText("查看原话").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("这条记忆从旧资料迁入，尚无已核验的聊天原话来源。").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("这条记忆由玩家手动记录，没有聊天原话来源。").assertDoesNotExist()
        } finally { runBlocking { db.sessionDao().deleteById(id) } }
    }
}
