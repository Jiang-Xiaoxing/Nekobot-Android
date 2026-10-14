package com.nekobot.app.data.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class OpenAIResponsesProtocolTest {
    @Test
    fun `function call output preserves tool images as structured input images`() {
        val url = "data:image/jpeg;base64,AA=="
        val payload = OpenAIResponsesProtocol.buildPayload(
            model = "gpt-test",
            messages = listOf(mapOf(
                "role" to "tool", "tool_call_id" to "call-image",
                "content" to buildToolMessageContent(mapOf("path" to "part.jpg", "_image_urls" to listOf(url)))
            )),
            stream = true, extra = emptyMap()
        )
        val output = (payload["input"] as List<*>).single() as Map<*, *>
        val blocks = output["output"] as List<*>

        assertEquals("function_call_output", output["type"])
        assertEquals("call-image", output["call_id"])
        assertTrue(((blocks[0] as Map<*, *>)["text"] as String).contains("part.jpg"))
        assertEquals(mapOf("type" to "input_image", "image_url" to url), blocks[1])
        assertTrue(((blocks[0] as Map<*, *>)["text"] as String).contains("base64").not())
    }

    @Test
    fun `plain text function output remains a string`() {
        val payload = OpenAIResponsesProtocol.buildPayload(
            model = "gpt-test",
            messages = listOf(mapOf("role" to "tool", "tool_call_id" to "call-text", "content" to "result")),
            stream = true, extra = emptyMap()
        )
        val output = (payload["input"] as List<*>).single() as Map<*, *>
        assertEquals("result", output["output"])
    }
    @Test
    fun `multimodal input preserves images and message roles`() {
        val payload = OpenAIResponsesProtocol.buildPayload(
            model = "gpt-test",
            messages = listOf(
                mapOf("role" to "user", "content" to listOf(
                    mapOf("type" to "text", "text" to "Read this image"),
                    mapOf("type" to "image_url", "image_url" to mapOf(
                        "url" to "data:image/png;base64,AA==", "detail" to "high"))
                )),
                mapOf("role" to "assistant", "content" to listOf(mapOf("type" to "text", "text" to "1234"))),
                mapOf("role" to "user", "content" to listOf(mapOf(
                    "type" to "input_image", "file_id" to "file-image", "detail" to "auto")))
            ),
            stream = true, extra = emptyMap()
        )
        val input = payload["input"] as List<*>
        val user = input[0] as Map<*, *>
        val blocks = user["content"] as List<*>
        assertEquals("user", user["role"])
        assertEquals(mapOf("type" to "input_text", "text" to "Read this image"), blocks[0])
        assertEquals(mapOf("type" to "input_image", "image_url" to "data:image/png;base64,AA==", "detail" to "high"), blocks[1])
        val assistant = input[1] as Map<*, *>
        assertEquals("assistant", assistant["role"])
        assertEquals(listOf(mapOf("type" to "output_text", "text" to "1234")), assistant["content"])
        assertEquals(listOf(mapOf("type" to "input_image", "file_id" to "file-image", "detail" to "auto")),
            (input[2] as Map<*, *>)["content"])
    }

    @Test
    fun `build payload separates instructions and flattens tools`() {
        val payload = OpenAIResponsesProtocol.buildPayload(
            model = "gpt-test",
            messages = listOf(
                mapOf("role" to "system", "content" to "Follow the rules"),
                mapOf("role" to "user", "content" to "Hello")
            ),
            stream = false,
            extra = mapOf(
                "max_tokens" to 2048,
                "tools" to listOf(
                    mapOf(
                        "type" to "function",
                        "function" to mapOf(
                            "name" to "lookup",
                            "description" to "Look up a value",
                            "parameters" to mapOf("type" to "object")
                        )
                    )
                )
            )
        )

        assertEquals("Follow the rules", payload["instructions"])
        assertEquals(true, payload["stream"])
        assertEquals(false, payload["store"])
        assertEquals(2048, payload["max_output_tokens"])
        val tools = payload["tools"] as List<*>
        val tool = tools.first() as Map<*, *>
        assertEquals("function", tool["type"])
        assertEquals("lookup", tool["name"])
        assertTrue("function" !in tool)
    }

    @Test
    fun `parse response completed returns text tools and usage`() {
        val event = """
            {
              "type": "response.completed",
              "response": {
                "status": "completed",
                "output": [
                  {
                    "type": "message",
                    "content": [{"type": "output_text", "text": "hello"}]
                  },
                  {
                    "type": "function_call",
                    "call_id": "call_1",
                    "name": "lookup",
                    "arguments": "{\"key\":\"value\"}"
                  }
                ],
                "usage": {
                  "input_tokens": 10,
                  "output_tokens": 4,
                  "total_tokens": 14
                }
              }
            }
        """.trimIndent()

        val parsed = requireNotNull(OpenAIResponsesProtocol.parseStreamFinalResponse(event))

        assertEquals("hello", parsed.content)
        assertEquals(14, parsed.usage["total"])
        assertEquals("lookup", parsed.toolCalls.single()["name"])
        assertEquals("tool_calls", parsed.finishReason)
    }

    @Test
    fun `completed response maps to stop so tool loop does not repeat`() = runBlocking {
        var modelCalls = 0
        val result = runToolCallLoop(
            initialMessages = listOf(mapOf("role" to "user", "content" to "look it up")),
            modelCall = { messages, _ ->
                modelCalls += 1
                val parsed = if (modelCalls == 1) {
                    OpenAIResponsesProtocol.parseNonStreamResponse(
                        mapOf(
                            "status" to "completed",
                            "output" to listOf(
                                mapOf(
                                    "type" to "function_call",
                                    "call_id" to "call_1",
                                    "name" to "lookup",
                                    "arguments" to """{"key":"value"}"""
                                )
                            )
                        )
                    )
                } else {
                    assertTrue(messages.any { it["role"] == "tool" })
                    OpenAIResponsesProtocol.parseNonStreamResponse(
                        mapOf(
                            "status" to "completed",
                            "output" to listOf(
                                mapOf(
                                    "type" to "message",
                                    "content" to listOf(
                                        mapOf("type" to "output_text", "text" to "done")
                                    )
                                )
                            )
                        )
                    )
                }
                buildMap {
                    put("content", parsed.content)
                    put("finish_reason", parsed.finishReason)
                    if (parsed.toolCalls.isNotEmpty()) put("tool_calls", parsed.toolCalls)
                }
            },
            toolExecutor = { _, _, _, _ -> mapOf("result" to "value") }
        )

        assertEquals(2, modelCalls)
        assertEquals("done", result.finalContent)
    }

    @Test
    fun `parse stream delta returns only output text`() {
        assertEquals(
            "hi",
            OpenAIResponsesProtocol.parseStreamChunk(
                """{"type":"response.output_text.delta","delta":"hi"}"""
            )
        )
        assertEquals(
            null,
            OpenAIResponsesProtocol.parseStreamChunk(
                """{"type":"response.created","response":{}}"""
            )
        )
        assertEquals(
            "subscription required",
            OpenAIResponsesProtocol.parseStreamError(
                """{"type":"response.failed","response":{"error":{"message":"subscription required"}}}"""
            )
        )
    }

    @Test
    fun `done-only stream events recover final text`() {
        val textDone = requireNotNull(
            OpenAIResponsesProtocol.parseStreamFinalResponse(
                """{"type":"response.output_text.done","text":"final text"}"""
            )
        )
        val itemDone = requireNotNull(
            OpenAIResponsesProtocol.parseStreamFinalResponse(
                """{"type":"response.output_item.done","item":{"type":"message","content":[{"type":"text","text":"item text"}]}}"""
            )
        )

        assertEquals("final text", textDone.content)
        assertEquals("item text", itemDone.content)
    }

    @Test
    fun `compatible response shapes recover top-level text and chat choices`() {
        val topLevel = OpenAIResponsesProtocol.parseNonStreamResponse(
            mapOf("output_text" to "top-level text", "status" to "completed")
        )
        val chatCompatible = OpenAIResponsesProtocol.parseNonStreamResponse(
            mapOf(
                "choices" to listOf(
                    mapOf(
                        "message" to mapOf("content" to "chat-compatible text"),
                        "finish_reason" to "stop"
                    )
                )
            )
        )

        assertEquals("top-level text", topLevel.content)
        assertEquals("chat-compatible text", chatCompatible.content)
    }

    @Test
    fun `function call semantic events are parsed without completed event`() {
        val added = OpenAIResponsesProtocol.parseStreamToolCallDeltas(
            """{"type":"response.output_item.added","output_index":2,"item":{"type":"function_call","call_id":"call_2","name":"lookup","arguments":""}}"""
        ).single()
        val arguments = OpenAIResponsesProtocol.parseStreamToolCallDeltas(
            """{"type":"response.function_call_arguments.done","output_index":2,"arguments":"{\"key\":\"value\"}"}"""
        ).single()
        val done = requireNotNull(
            OpenAIResponsesProtocol.parseStreamFinalResponse(
                """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_2","name":"lookup","arguments":"{\"key\":\"value\"}"}}"""
            )
        )

        assertEquals(2, added.index)
        assertEquals("lookup", added.nameChunk)
        assertEquals("{\"key\":\"value\"}", arguments.initialArgumentsJson)
        assertEquals("lookup", done.toolCalls.single()["name"])
    }

    @Test
    fun `reasoning effort and summary delta are supported`() {
        val payload = OpenAIResponsesProtocol.buildPayload(
            model = "gpt-5",
            messages = listOf(mapOf("role" to "user", "content" to "think")),
            stream = true,
            extra = mapOf("reasoning_effort" to "high")
        )

        val reasoning = payload["reasoning"] as Map<*, *>
        assertEquals("high", reasoning["effort"])
        assertEquals("auto", reasoning["summary"])
        assertEquals(
            "step one",
            OpenAIResponsesProtocol.parseStreamThinkingChunk(
                """{"type":"response.reasoning_summary_text.delta","delta":"step one"}"""
            )
        )
    }
}
