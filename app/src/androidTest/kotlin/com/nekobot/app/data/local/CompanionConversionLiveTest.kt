package com.nekobot.app.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** 显式接口专项：经正常选模、客户端、隔离转换和自动导入，测试资料全部合成。 */
class CompanionConversionLiveTest {
    @Test fun realModelRebuildsEpisodesRelationshipsAndFactsFromOrdinaryHistory() = runBlocking<Unit> {
        val endpoint = InstrumentationRegistry.getArguments().getString("live_conversion_endpoint").orEmpty()
        assumeTrue(endpoint.startsWith("http://127.0.0.1:"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previousProfile = ServiceContainer.prefs.activeDbName
        val previousMode = ServiceContainer.prefs.appMode
        val profile = "live-conversion-${UUID.randomUUID()}"
        try {
            ServiceContainer.switchAppMode(AppMode.LOCAL); ServiceContainer.switchLocalDb(profile)
            val db = NekobotDatabase.get(context, profile); val repo = ServiceContainer.localRepository
            db.characterDao().upsert(LocalCharacterEntity("c", "转换接口测试角色", createdAt = "old", updatedAt = "old"))
            val source = LocalSessionEntity("s", "普通会话原话", characterId = "c", createdAt = "old", updatedAt = "old")
            db.sessionDao().upsert(source)
            val texts = listOf(
                "2026年9月12日，我们一起给流浪猫橘子搭了纸箱小窝，结果它当天就在里面睡着了。",
                "是呀，看见橘子睡进去，我们都很开心。",
                "我原来喜欢甜奶茶，现在明确改成不加糖的茉莉茶。我们约好了10月20日一起整理照片。",
                "好，记住新口味和整理照片的约定了。",
                "之前说我养狗不对，我没有养狗。今天我们已经把约好的照片整理完了，不用再提醒我。",
                "好，照片已经整理完成，约定也完成了。",
                "我们已经确认是恋人关系了。上次旅行我记不清具体日期，别编日期。",
                "嗯，我们彼此确认了恋人关系。旅行的日期暂时留空。",
                "还有，我不吃香菜。"
            )
            texts.forEachIndexed { i, text -> db.messageDao().upsert(LocalMessageEntity("m$i", "s", if(i%2==0) "user" else "assistant", text,
                timestamp = "2026-10-09T09:00:00Z", createdAt = "2026-10-09T09:00:00Z")) }
            db.memoryDao().upsert(LocalCharacterMemoryEntity("legacy", "c", "local-user", "long", "user_persona", "错误旧记忆", "", "用户养狗并和角色一起去过火星", 1, createdAt = "old"))
            repo.upsertAiModel(LocalAiModelEntity("live-conversion", "真实转换测试", "openai_chat", apiKey = "synthetic-local-bridge",
                baseUrl = endpoint, model = "mimo-v2.5", maxTokens = 6000, maxContextLength = 32768,
                supportsTools = false, supportsStream = false, createdAt = "old"))
            repo.selectCompanionConversionModel("live-conversion")
            val preview = repo.previewCompanionTransfer("s")
            val target = db.sessionDao().getById(repo.convertCompanionTransfer(preview.jobId, "转换后的角色", "live-conversion") {})!!
            val store = CompanionMemoryStore(db); val scope = store.ensureScope(target)
            val saved = store.validatedMemories(scope).filter { it.current }
            assertTrue("缺少由原话重建的经历", saved.any { it.category == "episode" && it.content.contains("橘子") && it.eventDate == "2026-09-12" })
            assertTrue("缺少双方关系", saved.any { it.category == "relationship" && it.content.contains("恋人") })
            assertTrue("最后一条原话未处理", saved.any { it.content.contains("香菜") })
            assertTrue("口味纠正未保存", saved.any { it.content.contains("茉莉") && (it.content.contains("不加糖") || it.content.contains("无糖")) })
            assertFalse("旧记忆无原话内容被当真", saved.any { it.content.contains("火星") })
            assertFalse("把旧约定保留成未完成", saved.any { it.category == "promise" && it.content.contains("照片") && it.status == "active" })
            assertTrue("遗漏已完成的照片整理", saved.any { it.content.contains("照片") && (it.status == "completed" || it.content.contains("完成")) })
            assertFalse("给未知旅行编造日期", saved.any { it.content.contains("旅行") && it.eventDate != null })
            assertFalse("擅自把旅行写成双方共同参与", saved.any { it.content.contains("旅行") && (it.content.contains("一起") || it.content.contains("两人")) })
            assertTrue("未保存对养狗旧记忆的纠正", saved.any { it.content.contains("狗") && (it.content.contains("没有") || it.content.contains("未养") || it.content.contains("不养")) })
            assertTrue(saved.all { db.companionMemoryDao().sources(it.id).isNotEmpty() })
            db.sessionDao().deleteById("s"); db.characterDao().deleteById("c")
            assertEquals(saved.size, store.validatedMemories(scope).count { it.current })
            println("CONVERSION_LIVE_OK memories=${saved.size} categories=${saved.map { it.category }.distinct()} originals=${texts.size}")
            repo.deleteCompanionTransferBackup(preview.jobId)
        } finally {
            ServiceContainer.switchLocalDb(previousProfile); ServiceContainer.switchAppMode(previousMode)
            context.deleteDatabase("$profile.db")
        }
    }
}
