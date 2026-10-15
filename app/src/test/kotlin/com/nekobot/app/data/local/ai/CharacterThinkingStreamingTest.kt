package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 每个 SSE 包独立交付：读取下一个包前，上一包必须已经通过显示回调，不能只验最终字符串。 */
class CharacterThinkingStreamingTest {
    private data class Step(val field: String, val chunk: String, val content: String, val thinking: String)
    private val model = LocalAiModelEntity(id = "live-test", name = "Live", protocol = OpenAIChatProtocol.name,
        model = "custom-chat", apiKey = "test-key", baseUrl = "https://api.example.com/v1", createdAt = "2026-10-15")
    private val prompt = characterThinkingPrompt(CharacterThinkingSettings(), "林小满", "小星")
    private val messages get() = withCharacterThinkingRequest(listOf(mapOf("role" to "user", "content" to "没什么")), prompt)

    private suspend fun verify(mode: String, steps: List<Step>) {
        val content = StringBuilder()
        val thinking = StringBuilder()
        val checkpoints = steps.map { CountDownLatch(if (it.content.isEmpty() && it.thinking.isEmpty()) 0 else 1) }
        fun checkProgress() {
            steps.forEachIndexed { index, step ->
                if (content.toString() == step.content && thinking.toString() == step.thinking) checkpoints[index].countDown()
            }
        }
        val relay = CharacterThinkingStream(true, LocalAiStreamCallbacks(
            onStart = { content.setLength(0); thinking.setLength(0) },
            onContentChunk = { content.append(it); checkProgress() },
            onThinkingChunk = { thinking.append(it); checkProgress() }))
        val requests = mutableListOf<JsonObject>()
        val rawThinking = steps.filter { it.field == "reasoning_content" }.joinToString("") { it.chunk }
        val rawContent = steps.filter { it.field == "content" }.joinToString("") { it.chunk }
        val packets = steps.map { step ->
            "data: " + Gson().toJson(mapOf("choices" to listOf(mapOf("delta" to mapOf(step.field to step.chunk))))) + "\n\n"
        } + "data: [DONE]\n\n"
        var packet = 0
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (packet > 0 && packet <= steps.size) {
                    assertTrue("$mode 第${packet}包在后续数据到达前未显示，仍在等待响应结束",
                        checkpoints[packet - 1].await(2, TimeUnit.SECONDS))
                }
                if (packet == packets.size) return -1L
                val bytes = packets[packet++].toByteArray(Charsets.UTF_8)
                sink.write(bytes)
                return bytes.size.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        val client = LocalAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests += JsonParser.parseString(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).asJsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Mock")
                .body(object : ResponseBody() {
                    override fun contentType() = "text/event-stream".toMediaType()
                    override fun contentLength() = -1L
                    override fun source() = source
                }).build()
        }.build())
        val extra = mapOf(CHARACTER_THINKING_EXTRA to true, "reasoning_effort" to "high")
        if (mode == "callbacks") {
            val raw = client.chatOnce(model, messages, extra, streamCallbacks = relay.callbacks)
            assertEquals(rawThinking, raw.thinkingContent)
            assertEquals(rawContent, raw.content)
            val saved = relay.resolve(raw)
            assertEquals(steps.last().thinking, saved.thinkingContent)
            assertEquals(steps.last().content, saved.content)
        } else {
            val flow = if (mode == "direct") client.chatStream(model, messages, extra)
                else client.chatStreamWithFailover(listOf(model), messages, extra)
            flow.buffer(0).collect { event ->
                when (event) {
                    is RealtimeEvent.StreamStart -> relay.callbacks.onStart()
                    is RealtimeEvent.StreamChunk -> relay.callbacks.onContentChunk(event.chunk)
                    is RealtimeEvent.ReasoningChunk -> relay.callbacks.onThinkingChunk(event.chunk)
                    is RealtimeEvent.Error -> fail(event.message)
                    else -> Unit
                }
            }
            relay.finish()
        }
        assertEquals(steps.last().content, content.toString())
        assertEquals(steps.last().thinking, thinking.toString())
        assertEquals(1, requests.size)
        val request = requests.single()
        assertTrue(request["stream"].asBoolean)
        assertEquals("high", request["reasoning_effort"].asString)
        val sent = request["messages"].asJsonArray
        assertEquals(2, sent.size())
        assertEquals("system", sent[0].asJsonObject["role"].asString)
        assertEquals("## $CHARACTER_THINKING_STYLE_KEY\n$prompt", sent[0].asJsonObject["content"].asString)
        assertEquals("没什么", sent[1].asJsonObject["content"].asString)
    }

    @Test
    fun shortNativeThoughtAndReplyAreVisibleBeforeNextSsePacketInAllThreePaths() = runBlocking {
        for (mode in listOf("callbacks", "direct", "queue")) verify(mode, listOf(
            Step("reasoning_content", "我先", "", "我先"),
            Step("reasoning_content", "想想。", "", "我先想想。"),
            Step("content", "怎么", "怎么", "我先想想。"),
            Step("content", "啦？", "怎么啦？", "我先想想。")))
    }

    @Test
    fun splitTagsAndShortReplyDoNotWaitFor240CharactersOrDone() = runBlocking {
        for (mode in listOf("callbacks", "direct", "queue")) verify(mode, listOf(
            Step("content", "<thi", "", ""),
            Step("content", "nk>我先", "", "我先"),
            Step("content", "想想。", "", "我先想想。"),
            Step("content", "</thi", "", "我先想想。"),
            Step("content", "nk>怎么", "怎么", "我先想想。"),
            Step("content", "啦？", "怎么啦？", "我先想想。")))
    }

    @Test
    fun reportedEchoIsHiddenWhileTaggedFallbackAndReplyStillStream() = runBlocking {
        for (mode in listOf("callbacks", "direct", "queue")) verify(mode, listOf(
            Step("reasoning_content", "按thinking_style放在思考和回复里。", "", ""),
            Step("reasoning_content", "底层控制提示：接口没有原生思考字段时，在正文开头用「」包住第一人称内心想法。我应该输出思考标签包裹的想法。", "", ""),
            Step("content", "<think>我有点", "", "我有点"),
            Step("content", "想他了。</think>", "", "我有点想他了。"),
            Step("content", "我在", "我在", "我有点想他了。"),
            Step("content", "这里。", "我在这里。", "我有点想他了。")))
    }
}
