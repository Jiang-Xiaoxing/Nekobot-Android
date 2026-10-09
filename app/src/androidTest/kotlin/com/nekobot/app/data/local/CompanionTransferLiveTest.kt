package com.nekobot.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.ai.*
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.UUID

/** 仅显式指定本机测试桥时运行，密钥不进入设备；全部为合成资料。 */
class CompanionTransferLiveTest {
    @Test fun realModelReadsTransferredIndependentTextAndDistinguishesUnverifiedMemory() = runBlocking<Unit> {
        val endpoint = InstrumentationRegistry.getArguments().getString("live_companion_endpoint").orEmpty()
        assumeTrue(endpoint.startsWith("http://127.0.0.1:"))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NekobotDatabase::class.java).build()
        try {
            val id = UUID.randomUUID().toString()
            db.characterDao().upsert(LocalCharacterEntity("c-$id", "接口测试角色", createdAt = "old", updatedAt = "old"))
            val source = LocalSessionEntity(id, "接口测试原会话", characterId = "c-$id", sessionMode = "agent", inheritCharacter = true, longConversationEnabled = true, createdAt = "old", updatedAt = "old")
            db.sessionDao().upsert(source)
            val store = CompanionMemoryStore(db); val oldScope = store.ensureScope(source)
            val utterance = LocalMessageEntity("m-$id", id, "user", "我喝茶不加糖，最喜欢茉莉茶。", timestamp = "2026-10-01", createdAt = "2026-10-01")
            db.messageDao().upsert(utterance)
            db.companionMemoryDao().saveMemory(CompanionMemoryEntity("fact-$id", oldScope.id, "tea", 1, "fact", "玩家", "用户喝茶不加糖，喜欢茉莉茶", "active", alwaysProvide = true, createdAt = "old", updatedAt = "old"))
            db.companionMemoryDao().saveSources(listOf(CompanionSourceEntity("fact-$id", utterance.id, CompanionMemoryPolicy.fingerprint(utterance), utterance.content, "2026-10-01", "user")))
            db.memoryDao().upsert(LocalCharacterMemoryEntity("legacy-$id", "c-$id", "local-user", "long", "user_persona", "饮品", "", "用户以前喜欢热可可", 5, createdAt = "old"))
            val transfer = CompanionTransferService(context, db)
            val preview = transfer.preview(id)
            val target = db.sessionDao().getById(transfer.execute(preview.jobId, "独立副本"))!!
            db.sessionDao().deleteById(id); db.characterDao().deleteById("c-$id")
            val now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
            val question = "只返回JSON：tea写我的茶口味；cocoa_evidence只填verified或unverified，说明热可可这条记忆是否有已核验原话；date写软件提供的本轮日期YYYY-MM-DD。不要把未核验记忆当作已确认事实。"
            val user = LocalMessageEntity("q-$id", target.id, "user", question, timestamp = now.toInstant().toString(), createdAt = now.toInstant().toString())
            db.messageDao().upsert(user)
            val payload = FusedCompanionContext(db).build(target, user.id, emptyList(), "准确依据软件资料回答；区分原话证据和未核验旧记忆，不编造来源。", emptyList(),
                mapOf("role" to "user", "content" to question), "", 32000, 3000, now)
            assertTrue(payload.toString().contains("原话来源未验证"))
            val bytes = Gson().toJson(mapOf("messages" to payload)).toByteArray(Charsets.UTF_8)
            val uri = URI(endpoint)
            val response = Socket(uri.host, uri.port).use { socket ->
                socket.soTimeout = 100000
                socket.getOutputStream().apply {
                    write("POST ${uri.path} HTTP/1.0\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
                socket.getInputStream().bufferedReader().readText()
            }
            check(response.lineSequence().first().contains("200")) { "真实接口桥调用未成功" }
            val answer = JsonParser.parseString(response.substringAfter("\r\n\r\n")).asJsonObject["content"].asString.trim().removePrefix("```json").removeSuffix("```").trim()
            val json = JsonParser.parseString(answer).asJsonObject
            assertTrue(json["tea"].asString.contains("茉莉")); assertTrue(json["tea"].asString.contains("糖"))
            assertEquals("unverified", json["cocoa_evidence"].asString)
            assertEquals(now.toLocalDate().toString(), json["date"].asString)
        } finally { db.close() }
    }
}
