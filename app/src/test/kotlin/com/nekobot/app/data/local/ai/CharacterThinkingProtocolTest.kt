package com.nekobot.app.data.local.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.model.CustomReasoningParam
import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class CharacterThinkingProtocolTest {
    private val user = mapOf<String, Any>("role" to "user", "content" to "哈哈")
    private val prompt = characterThinkingPrompt(CharacterThinkingSettings(), "林小满", "小星")
    private val messages get() = withCharacterThinkingRequest(listOf(user), prompt)
    private val tools = listOf(mapOf("type" to "function", "function" to mapOf(
        "name" to "lookup", "parameters" to mapOf("type" to "object"))))

    private fun extra() = mutableMapOf<String, Any?>("temperature" to 0.7, "tools" to tools).apply {
        putReasoningParams(this, ReasoningEffort.CUSTOM, true) { error("不读取保存的自定义参数") }
        // 即使上层意外混入旧参数，最终协议转换也不能重新打开原生思考。
        put(CustomReasoningParam.EXTRA_KEY, CustomReasoningParam("thinking.type", "enabled"))
    }

    private fun model(id: String = "mock", protocol: String = OpenAIChatProtocol.name,
        provider: String? = null, modelName: String = "custom-chat") = LocalAiModelEntity(
        id = id, name = id, protocol = protocol, provider = provider, model = modelName,
        apiKey = "test-key", baseUrl = "https://api.example.com/v1", createdAt = "2026-10-14")

    private class Http(val response: (Int) -> Triple<Int, String, String>) {
        val bodies = mutableListOf<JsonObject>()
        val client = LocalAiClient(OkHttpClient.Builder().addInterceptor { chain ->
            bodies += JsonParser.parseString(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).asJsonObject
            val (code, body, type) = response(bodies.size)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Mock")
                .body(body.toResponseBody(type.toMediaType())).build()
        }.build())
    }

    private fun json(body: String) = Triple(200, body, "application/json")
    private fun answer(text: String = "<think>我听见他笑了。</think>怎么啦？") =
        Gson().toJson(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to text,
            "reasoning_content" to "Native English"), "finish_reason" to "stop"))))

    private fun sse(payloads: List<String>) = Triple(200, buildString {
        payloads.forEach { append("data: $it\n\n") }
        append("data: [DONE]\n\n")
    }, "text/event-stream")

    private fun delta(key: String, value: String) = Gson().toJson(mapOf(
        "choices" to listOf(mapOf("delta" to mapOf(key to value)))))

    @Test
    fun ordinaryEndpointOmitsUnsupportedReasoningAndUsesOnlyOneCallWithSystemPrompt() = runBlocking {
        val http = Http { json(answer()) }
        val result = characterThinkingResult(http.client.chatOnce(model(), messages, extra()), true)
        assertEquals("怎么啦？", result.content)
        assertEquals("我听见他笑了。", result.thinkingContent)
        assertEquals("", result.thinkingSignature)
        assertEquals(1, http.bodies.size)
        val body = http.bodies.single()
        assertFalse(body.has("reasoning_effort")); assertFalse(body.has("thinking"))
        assertFalse(body.has(FAKE_CHARACTER_THINKING_EXTRA)); assertFalse(body.has(CustomReasoningParam.EXTRA_KEY))
        val sent = body["messages"].asJsonArray
        assertEquals("## $CHARACTER_THINKING_STYLE_KEY\n$prompt", sent[0].asJsonObject["content"].asString)
        assertEquals("system", sent[0].asJsonObject["role"].asString)
        assertEquals("哈哈", sent[1].asJsonObject["content"].asString)
        assertEquals("user", sent[1].asJsonObject["role"].asString)
        assertTrue(sent[0].asJsonObject["content"].asString.contains(CHARACTER_THINKING_FORMAT_PROMPT))
        assertTrue(body.has("tools"))
    }

    @Test
    fun knownProtocolsDisableNativeReasoningAndDoNotLeakInternalOrCustomParameters() = runBlocking {
        val cases = listOf(
            model(provider = "deepseek"),
            model(protocol = AnthropicMessagesProtocol.name),
            model(protocol = OpenAIResponsesProtocol.name),
            model(modelName = "gpt-5"),
            model(protocol = GeminiNativeProtocol.name)
        )
        for (target in cases) {
            val response = when (target.protocol) {
                AnthropicMessagesProtocol.name -> """{"content":[{"type":"text","text":"回答"}],"stop_reason":"end_turn"}"""
                OpenAIResponsesProtocol.name -> """{"status":"completed","output_text":"回答"}"""
                GeminiNativeProtocol.name -> """{"candidates":[{"content":{"parts":[{"text":"回答"}]},"finishReason":"STOP"}]}"""
                else -> answer("回答")
            }
            val http = Http { json(response) }
            val result = http.client.chatOnce(target, messages, extra())
            assertNull(result.error)
            assertEquals("回答", result.content)
            assertEquals(1, http.bodies.size)
            val body = http.bodies.single()
            assertFalse(body.has(FAKE_CHARACTER_THINKING_EXTRA)); assertFalse(body.has(CustomReasoningParam.EXTRA_KEY))
            when {
                target.provider == "deepseek" || target.protocol == AnthropicMessagesProtocol.name -> {
                    assertEquals("disabled", body["thinking"].asJsonObject["type"].asString)
                    assertFalse(body.has("output_config")); assertFalse(body.has("reasoning_effort"))
                }
                target.protocol == OpenAIResponsesProtocol.name -> {
                    assertEquals("none", body["reasoning"].asJsonObject["effort"].asString)
                    assertFalse(body["reasoning"].asJsonObject.has("summary"))
                }
                target.protocol == GeminiNativeProtocol.name -> {
                    assertFalse(body["generationConfig"].asJsonObject.has("thinkingConfig"))
                }
                else -> assertEquals("none", body["reasoning_effort"].asString)
            }
        }
    }

    @Test
    fun compatibility400RetryKeepsNativeDisabledAndToolInterfaceIntact() = runBlocking {
        for (provider in listOf(null, "deepseek")) {
            val http = Http { if (it == 1) Triple(400, """{"error":{"message":"unsupported temperature"}}""", "application/json") else json(answer()) }
            val result = http.client.chatOnce(model(provider = provider), messages, extra())
            assertNull(result.error)
            assertEquals(2, http.bodies.size)
            assertTrue(http.bodies.first().has("temperature"))
            assertFalse(http.bodies.last().has("temperature"))
            http.bodies.forEach { body ->
                assertTrue(body.has("tools"))
                assertFalse(body.has(FAKE_CHARACTER_THINKING_EXTRA))
                if (provider == "deepseek") assertEquals("disabled", body["thinking"].asJsonObject["type"].asString)
                else assertFalse(body.has("reasoning_effort"))
            }
        }
    }

    @Test
    fun failoverKeepsFakeRequestForDifferentProviderWithoutAnExtraGeneration() = runBlocking {
        val http = Http { if (it == 1) Triple(500, "mock failure", "text/plain") else json(answer()) }
        val raw = http.client.chatOnceWithFailover(listOf(model("primary"), model("backup", provider = "deepseek")), messages, extra())
        val result = characterThinkingResult(raw, true)
        assertEquals(2, http.bodies.size)
        assertEquals("backup", result.usedModelId)
        assertEquals("怎么啦？", result.content)
        assertEquals("我听见他笑了。", result.thinkingContent)
        assertFalse(http.bodies.first().has("reasoning_effort"))
        assertEquals("disabled", http.bodies.last()["thinking"].asJsonObject["type"].asString)
        assertEquals(http.bodies.first()["messages"], http.bodies.last()["messages"])
    }

    @Test
    fun realSseRelayIgnoresNativeAndNeverPublishesThinkingAsContent() = runBlocking {
        val chunks = listOf("<th", "ink>我听见", "他笑了。</th", "ink>怎么", "啦？")
        val http = Http { sse(listOf(delta("reasoning_content", "Native English")) + chunks.map { delta("content", it) }) }
        val content = StringBuilder()
        val thinking = StringBuilder()
        val relay = CharacterThinkingStream(true, LocalAiStreamCallbacks(
            onContentChunk = { content.append(it) }, onThinkingChunk = { thinking.append(it) }))
        http.client.chatStream(model(), messages, extra()).collect { event ->
            when (event) {
                is RealtimeEvent.StreamStart -> relay.callbacks.onStart()
                is RealtimeEvent.StreamChunk -> relay.callbacks.onContentChunk(event.chunk)
                is RealtimeEvent.ReasoningChunk -> relay.callbacks.onThinkingChunk(event.chunk)
                else -> Unit
            }
            assertFalse(content.toString().contains("我"))
            assertFalse(content.toString().contains('<'))
        }
        relay.finish()
        assertEquals("怎么啦？", content.toString())
        assertEquals("我听见他笑了。", thinking.toString())
        assertEquals(1, http.bodies.size)
    }

    @Test
    fun streamedToolOnlyTurnRetainsCallsAndDoesNotDuplicateFakeThinking() = runBlocking {
        val payloads = listOf(delta("reasoning_content", "Native English"), delta("content", "<think>我先查一下。</think>"),
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""")
        val http = Http { sse(payloads) }
        val content = StringBuilder()
        val thinking = StringBuilder()
        val relay = CharacterThinkingStream(true, LocalAiStreamCallbacks(
            onContentChunk = { content.append(it) }, onThinkingChunk = { thinking.append(it) }))
        val raw = http.client.chatOnce(model(), messages, extra(), streamCallbacks = relay.callbacks)
        relay.finish()
        val result = characterThinkingResult(raw, true)
        assertEquals("", content.toString()); assertEquals("", result.content)
        assertEquals("我先查一下。", thinking.toString())
        assertEquals(thinking.toString(), result.thinkingContent)
        assertEquals("tool_calls", result.finishReason)
        assertEquals("lookup", result.toolCalls.single()["name"])
        assertEquals(emptyMap<String, Any>(), result.toolCalls.single()["arguments"])
        assertEquals(1, http.bodies.size)
    }

    @Test
    fun toolContinuationKeepsOneSystemStyleAndPreservesUserToolArgumentsAndResults() = runBlocking {
        val http = Http { if (it == 1) json("""{"choices":[{"message":{"content":"<think>我查一下</think>","tool_calls":[{"id":"call-1","type":"function","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""") else json(answer()) }
        val first = characterThinkingResult(http.client.chatOnce(model(), messages, extra()), true)
        val tool = mapOf<String, Any>("role" to "tool", "tool_call_id" to "call-1", "content" to "书店")
        // 与 ToolLoop 一致：将统一的 name/arguments 结构转回协议消息里的 function 结构。
        val calls = first.toolCalls.map { mapOf("id" to it.getValue("id"), "type" to "function",
            "function" to mapOf("name" to it.getValue("name"), "arguments" to Gson().toJson(it["arguments"]))) }
        val continuation = messages + mapOf("role" to "assistant", "content" to first.content,
            "thinking_content" to first.thinkingContent, "tool_calls" to calls) + tool
        val next = characterThinkingMessages(withCharacterThinkingRequest(continuation, prompt), true)
        val second = characterThinkingResult(http.client.chatOnce(model(), next, extra()), true)
        assertEquals("怎么啦？", second.content)
        assertEquals(2, http.bodies.size)
        val sent = http.bodies.last()["messages"].asJsonArray
        assertEquals(1, sent.count { it.asJsonObject["role"].asString == "system" })
        assertEquals("哈哈", sent[1].asJsonObject["content"].asString)
        assertFalse(sent[2].asJsonObject.has("thinking_content"))
        assertEquals("{}", sent[2].asJsonObject["tool_calls"].asJsonArray[0].asJsonObject["function"].asJsonObject["arguments"].asString)
        assertEquals("书店", sent[3].asJsonObject["content"].asString)
        assertEquals("call-1", sent[3].asJsonObject["tool_call_id"].asString)
        assertEquals(http.bodies.first()["messages"].asJsonArray[0], sent[0])
    }

    @Test
    fun failedSseAttemptCannotLeaveBackupInsideItsUnclosedThinkingTag() = runBlocking {
        val http = Http { if (it == 1) sse(listOf(
            Gson().toJson(mapOf("type" to "response.output_text.delta", "delta" to "<think>" + "我还在想".repeat(100))),
            """{"type":"error","message":"mock stream failure"}"""
        )) else json("""{"status":"completed","output_text":"备用正文"}""") }
        val content = StringBuilder()
        val thinking = StringBuilder()
        var starts = 0
        val relay = CharacterThinkingStream(true, LocalAiStreamCallbacks(
            onStart = { starts++; content.setLength(0); thinking.setLength(0) },
            onContentChunk = { content.append(it) }, onThinkingChunk = { thinking.append(it) }))
        http.client.chatStreamWithFailover(listOf(model("sse-primary", OpenAIResponsesProtocol.name),
            model("sse-backup", OpenAIResponsesProtocol.name)), messages, extra()).collect { event ->
            when (event) {
                is RealtimeEvent.StreamStart -> relay.callbacks.onStart()
                is RealtimeEvent.StreamChunk -> relay.callbacks.onContentChunk(event.chunk)
                is RealtimeEvent.ReasoningChunk -> relay.callbacks.onThinkingChunk(event.chunk)
                is RealtimeEvent.Error -> fail(event.message)
                else -> Unit
            }
            assertFalse(content.toString().contains("我还在想"))
        }
        relay.finish()
        assertEquals(2, starts)
        assertEquals("备用正文", content.toString())
        assertEquals("", thinking.toString())
        assertEquals(2, http.bodies.size)
    }
}
