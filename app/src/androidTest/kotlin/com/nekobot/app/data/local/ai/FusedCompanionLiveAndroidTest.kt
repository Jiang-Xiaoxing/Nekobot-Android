package com.nekobot.app.data.local.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Opt-in real-provider tests, using synthetic dialogue only. Credentials stay on the host
 * bridge; no key, provider configuration, or personal chat is installed in the app. */
class FusedCompanionLiveAndroidTest {
    private val gson=Gson()
    private val http=OkHttpClient.Builder().callTimeout(100,TimeUnit.SECONDS).build()
    private fun endpoint(): String {
        val value=InstrumentationRegistry.getArguments().getString("live_companion_endpoint").orEmpty()
        assumeTrue("Live API test explicitly requires a local bridge",value.startsWith("http://127.0.0.1:"))
        return value
    }
    private fun call(endpoint: String, messages: List<Map<String,Any>>): String {
        val body=gson.toJson(mapOf("messages" to messages)).toByteArray(Charsets.UTF_8)
        val uri=java.net.URI(endpoint)
        check(uri.host=="127.0.0.1")
        // The test APK has its own network policy. This test-only loopback transport carries
        // synthetic text through adb reverse; the provider leg uses HTTPS on the host.
        return java.net.Socket(uri.host,uri.port).use { socket ->
            socket.soTimeout=100000
            socket.getOutputStream().apply {
                write("POST ${uri.path} HTTP/1.0\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                write(body); flush()
            }
            val text=socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
            check(text.lineSequence().first().contains("200")) { "Live bridge request failed" }
            JsonParser.parseString(text.substringAfter("\r\n\r\n")).asJsonObject["content"].asString
        }
    }
    @Test fun realExtractionReferenceBoundaryCorrectionAndRecallAnswer() = runBlocking {
        val endpoint=endpoint()
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=Room.inMemoryDatabaseBuilder(context,NekobotDatabase::class.java).build()
        try {
            FusedCompanionSchema.installTriggers(db.openHelper.writableDatabase)
            val session=LocalSessionEntity("live-s","Synthetic live",characterId="live-c",sessionMode="agent",
                inheritCharacter=true,longConversationEnabled=true,companionMode=true,createdAt="now",updatedAt="now")
            db.sessionDao().upsert(session)
            val store=CompanionMemoryStore(db); store.ensureScope(session)
            suspend fun say(id:String,text:String,role:String="user")=LocalMessageEntity(id,session.id,role,text,
                timestamp="2026-10-08T12:00:00Z",createdAt="2026-10-08T12:00:00Z").also { db.messageDao().upsert(it) }
            say("diet","我怕辣，喝茶不要加糖。这个口味我一直没变。")
            say("reply","明白，我们按你的口味来。"+"我们接着随意聊天，听着窗外的风声。".repeat(30),"assistant")
            // One complete recent reference turn fills the retained tail. This unique fact must
            // remain pending, even though the real extraction model sees it in the reference.
            var filler=""
            while (estimateLocalTextTokens(filler)<7800) filler+="我们刚才在随意闲聊窗外的风景，没有新的偏好或约定。"
            say("reference","下周我准备去成都。"+filler)
            say("reference-reply","我听着呢。","assistant")
            store.process(session,{system,material->call(endpoint,listOf(mapOf("role" to "system","content" to system),
                mapOf("role" to "user","content" to material)))},16000,force=true)
            var facts=store.validatedMemories(store.ensureScope(session)).filter { it.current }
            assertTrue("Real extraction should retain the diet preference",facts.any { it.content.contains("辣") })
            assertTrue("Real extraction should retain unsweetened tea",facts.any { it.content.contains("糖") })
            assertFalse("Reference-only Chengdu must not be committed",facts.any { it.content.contains("成都") })
            val budgets=FusedCompanionPolicy.budgets(14000)
            val recall=store.fusedRecall(session,"我平时吃喝有什么习惯？",budgets).first
            val answer=call(endpoint,listOf(mapOf("role" to "system","content" to "你是陪伴角色，只根据资料回答，不猜。"),
                mapOf("role" to "user","content" to "$recall\n玩家问：我平时吃喝有什么习惯？")))
            assertTrue("Answer should mention spice: $answer",answer.contains("辣"))
            assertTrue("Answer should mention sugar: $answer",answer.contains("糖"))

            // A later real extraction updates an existing fact, while rejecting an invented past.
            val diet=facts.first { it.content.contains("辣") }
            val correction=say("correction","之前怕辣是老习惯了，现在我已经改成喜欢吃辣。")
            val invented=say("invented","还记得去年我们一起去了海边吗？","assistant")
            val denial=say("denial","你记错了，我们从来没有一起去过海边。")
            val material=gson.toJson(mapOf("target" to listOf(correction,invented,denial).map {
                mapOf("id" to it.id,"role" to it.role,"text" to it.content) },"reference" to emptyList<Any>(),
                "existing" to facts.map { mapOf("key" to it.factKey,"content" to it.content,"category" to it.category,"locked" to false) }))
            val changes=JsonParser.parseString(call(endpoint,listOf(mapOf("role" to "system","content" to FusedCompanionPolicy.extractionPrompt),
                mapOf("role" to "user","content" to material)))).asJsonObject["changes"].asJsonArray
            assertTrue("Real model must identify the old diet key for correction",changes.any {
                val x=it.asJsonObject; x["target_key"]?.asString==diet.factKey && x["operation"]?.asString=="update" })
            assertFalse("Denied seaside event cannot become a new episode",changes.any {
                val x=it.asJsonObject; x["category"]?.asString=="episode" && x["operation"]?.asString in listOf("add","update") &&
                    x["content"]?.asString.orEmpty().contains("海边") })
            for (change in changes) {
                val fields=gson.fromJson(change,Map::class.java).entries.associate { it.key.toString() to requireNotNull(it.value) }
                store.fusedTool(session,"agent_memory_update",fields,change.asJsonObject["source_id"].asString)
            }
            facts=store.validatedMemories(store.ensureScope(session)).filter { it.current }
            assertTrue(facts.any { it.content.contains("喜欢") && it.content.contains("辣") })
            assertFalse(facts.any { it.category=="episode" && it.content.contains("海边") })
        } finally { db.close() }
    }
}
