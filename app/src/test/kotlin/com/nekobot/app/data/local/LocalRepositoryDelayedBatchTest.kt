package com.nekobot.app.data.local

import com.nekobot.app.data.model.Message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalRepositoryDelayedBatchTest {

    @Test
    fun `batched messages run hooks once persist in order and protect every saved id`() = runBlocking {
        val hookInputs = mutableListOf<String>()
        val persisted = mutableListOf<String>()

        val batch = prepareUserMessageBatch(
            messages = listOf("第一条", "第二条"),
            beforeSend = { content ->
                hookInputs += content
                "$content-已处理"
            },
            persist = { content ->
                persisted += content
                Message(
                    id = "message-${persisted.size}",
                    role = "user",
                    content = content
                )
            }
        )

        assertEquals(listOf("第一条", "第二条"), hookInputs)
        assertEquals(listOf("第一条-已处理", "第二条-已处理"), persisted)
        assertEquals(2, batch.savedMessages.size)
        assertEquals("第二条-已处理", batch.latestOutgoingMessage)
        assertEquals("message-2", batch.latestSavedMessage?.id)
        assertEquals(linkedSetOf("message-1", "message-2"), batch.protectedMessageIds)
    }
}
