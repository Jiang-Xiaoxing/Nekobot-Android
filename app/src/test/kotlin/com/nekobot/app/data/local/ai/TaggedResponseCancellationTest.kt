package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class TaggedResponseCancellationTest {
    @Test fun cancellationInterruptsAResponseBodyAfterHeadersArrive() = runBlocking {
        val received = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val server = ServerSocket(0)
        val worker = Thread {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().bufferedReader()
                var length = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                }
                repeat(length) { input.read() }
                val payload = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"
                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n" +
                    payload.toByteArray().size.toString(16) + "\r\n" + payload + "\r\n"
                socket.getOutputStream().write(response.toByteArray())
                socket.getOutputStream().flush()
                finish.await(10, TimeUnit.SECONDS)
            }
        }.apply { isDaemon = true; start() }
        try {
            val client = LocalAiClient()
            val model = LocalAiModelEntity("cancel", "Cancel", OpenAIChatProtocol.name,
                apiKey = "", baseUrl = "http://127.0.0.1:${server.localPort}/v1", model = "custom", createdAt = "now")
            val request = async(Dispatchers.IO) { runCatching {
                client.chatOnce(model, listOf(mapOf("role" to "user", "content" to "hello")),
                    requestTag = "body-cancel", streamCallbacks = LocalAiStreamCallbacks(onStreamObserved = { received.countDown() }))
            } }
            assertTrue(received.await(5, TimeUnit.SECONDS))
            client.cancelRequests("body-cancel")
            withTimeout(3000) { assertTrue(request.await().isFailure) }
        } finally { finish.countDown(); server.close(); worker.join(1000) }
    }
}
