package com.nekobot.app.data.local.ai

import com.nekobot.app.data.model.ReasoningEffort
import com.nekobot.app.data.local.LocalLogger
import com.nekobot.app.data.local.db.LocalAiModelEntity
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** 真正走本地Socket，不用立即返回的拦截器假装验证阻塞正文取消。 */
class CompanionRelationshipHttpTimeoutTest {
    private val response = """{"choices":[{"message":{"content":"{}"},"finish_reason":"stop"}]}"""
    private val messages = listOf(mapOf<String, Any>("role" to "user", "content" to "private-chat-sentinel"))
    private fun model(server: MockWebServer) = LocalAiModelEntity(id = "eval", name = "评估", protocol = OpenAIChatProtocol.name,
        model = "test-model", apiKey = "private-key-sentinel", baseUrl = server.url("/v1").toString(), createdAt = "2026-10-16")
    private fun extra(model: LocalAiModelEntity, timeout: Long) = CompanionRelationshipRequest.extra(model, ReasoningEffort.HIGH) +
        (CompanionRelationshipRequest.CALL_TIMEOUT_EXTRA to timeout)

    private fun checkTimeout(reply: MockResponse, outerCancellation: Boolean = false, expectsHeaders: Boolean = true) = runBlocking {
        MockWebServer().use { server ->
            val call = AtomicReference<Call>()
            val receivedHeaders = AtomicBoolean()
            val transport = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS)
                .addNetworkInterceptor { chain ->
                    call.set(chain.call())
                    chain.proceed(chain.request()).also { receivedHeaders.set(true) }
                }.build()
            val client = LocalAiClient(transport)
            val model = model(server)
            // QueueDispatcher.peek()会提前把throttleBody也用于读取POST请求体。
            // 用dispatch在完整收到请求后才选择慢响应，确保测到的是响应正文滴流。
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/warmup") MockResponse().setBody("{}") else reply
            }
            // 先预热JVM/本地连接，避免第一条请求的类加载耗时吃掉正文测试的短期限。
            transport.newCall(Request.Builder().url(server.url("/warmup")).build()).execute().use { it.body!!.string() }
            val baseline = server.requestCount
            call.set(null)
            receivedHeaders.set(false)
            val start = System.nanoTime()
            val error = runCatching {
                withTimeout(if (outerCancellation) 1200 else 5000) {
                    client.chatOnce(model, messages, extra(model, if (outerCancellation) 5000 else 1200), "timeout-test")
                }
            }.exceptionOrNull()
            assertNotNull("slow network should stop", error)
            if (outerCancellation) assertTrue(error is TimeoutCancellationException)
            else assertTrue("expected socket call timeout, got ${error?.javaClass}", error is java.io.InterruptedIOException)
            assertTrue("must stop before the slow body completes", (System.nanoTime() - start) / 1_000_000 < 3000)
            assertEquals("the measured evaluation must send exactly once", 1, server.requestCount - baseline)
            if (expectsHeaders) assertTrue("deadline must cover reading after response headers", receivedHeaders.get())
            assertTrue(call.get()?.isCanceled() == true)
            repeat(20) { if (transport.dispatcher.runningCallsCount() > 0) delay(20) }
            assertEquals(0, transport.dispatcher.runningCallsCount())
            for (name in listOf("activeHttpCalls", "taggedCalls")) {
                val field = LocalAiClient::class.java.getDeclaredField(name).apply { isAccessible = true }.get(client)
                assertEquals("must release $name", 0, when (field) { is Map<*, *> -> field.size; is Set<*> -> field.size; else -> -1 })
            }
        }
    }

    @Test fun delayedHeadersHaveHardCallDeadline() = checkTimeout(MockResponse().setBody(response).setHeadersDelay(4, TimeUnit.SECONDS), expectsHeaders = false)
    @Test fun headersArriveButBodyStallsHasHardCallDeadline() = checkTimeout(MockResponse().setBody(response).setBodyDelay(4, TimeUnit.SECONDS))
    @Test fun continuousTrickleDoesNotResetTotalCallDeadline() = checkTimeout(MockResponse().setBody(response).throttleBody(1, 100, TimeUnit.MILLISECONDS))
    @Test fun outerCoroutineCancellationInterruptsBodyReadImmediately() = checkTimeout(MockResponse().setBody(response).setBodyDelay(4, TimeUnit.SECONDS), true)
    @Test fun errorResponseBodyAlsoHasHardCallDeadline() = checkTimeout(MockResponse().setResponseCode(400).setBody(response).setBodyDelay(4, TimeUnit.SECONDS))
    @Test fun missingResponseIsCancelledAndDoesNotRetry() = checkTimeout(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE), expectsHeaders = false)

    // 让结果在 OkHttp 回调内立即恢复，稳定覆盖调用方先于 Dispatcher 收尾的时序。
    @Test fun successfulEvaluationReleasesCallsAndDoesNotChangeGlobalTimeoutOrLogPrivateMaterial() = runBlocking(Dispatchers.Unconfined) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response))
            val dispatcherIdle = CompletableDeferred<Unit>()
            val transport = OkHttpClient.Builder().build().apply {
                dispatcher.idleCallback = Runnable { dispatcherIdle.complete(Unit) }
            }
            val client = LocalAiClient(transport)
            val model = model(server)
            val requestTag = "success-test-${UUID.randomUUID()}"
            val result = client.chatOnce(model, messages, extra(model, 5000), requestTag)
            assertEquals("{}", result.content)
            assertEquals(1, server.requestCount)
            assertEquals(0, transport.callTimeoutMillis)
            // chatOnce 返回时 onResponse 可能尚未返回；等待真实空闲事件，不猜线程调度速度。
            withTimeout(5000) { dispatcherIdle.await() }
            assertEquals(0, transport.dispatcher.runningCallsCount())
            for (name in listOf("activeHttpCalls", "taggedCalls")) {
                val field = LocalAiClient::class.java.getDeclaredField(name).apply { isAccessible = true }.get(client)
                assertEquals("must release $name", 0, when (field) { is Map<*, *> -> field.size; is Set<*> -> field.size; else -> -1 })
            }
            val diagnostics = LocalLogger.listLogs().filter { it.tag == "RelationshipHttp" }
            assertTrue("must inspect diagnostics from this evaluation", diagnostics.any { it.message.startsWith("tag=$requestTag，") })
            val log = diagnostics.joinToString { it.message }
            assertFalse(log.contains("private-chat-sentinel"))
            assertFalse(log.contains("private-key-sentinel"))
            assertFalse(log.contains(server.hostName))
        }
    }

    @Test fun hardDeadlineAlsoCoversProtocolsThatRequireStreaming() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\"}\n\n").setBodyDelay(4, TimeUnit.SECONDS))
            val client = LocalAiClient()
            val model = model(server).copy(protocol = OpenAIResponsesProtocol.name)
            val error = runCatching { client.chatOnce(model, messages, extra(model, 350), "stream-test") }.exceptionOrNull()
            assertTrue(error is java.io.InterruptedIOException)
            assertEquals(1, server.requestCount)
        }
    }
}
