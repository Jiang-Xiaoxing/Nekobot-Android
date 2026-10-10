package com.nekobot.app.data.local.ai

import com.nekobot.app.data.local.db.LocalAiModelEntity
import com.nekobot.app.data.remote.RealtimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow

/** 每次切换重置临时气泡；最终仅保存成功模型的正文，复用健康、额度和超时策略。 */
internal fun LocalAiClient.characterQueueStream(
    models: List<LocalAiModelEntity>, messages: List<Map<String, Any>>, extra: Map<String, Any?>,
    requiredContextTokens: Int?, requestTag: String?, coordinator: FailoverCoordinator
) = channelFlow<RealtimeEvent> {
    try {
        val execution = coordinator.execute(models, "chat", requiredContextTokens) { model ->
            val relay = LocalStreamEventCoalescer(onEvent = { trySend(it).getOrThrow() })
            var hadContent = false
            var hadThinking = false
            relay.onStart()
            try {
                val result = chatOnce(model, messages, CharacterChatQueue.extraFor(model, extra), requestTag,
                    LocalAiStreamCallbacks(onContentChunk = { hadContent = true; relay.onContentChunk(it) },
                        onThinkingChunk = { hadThinking = true; relay.onReasoningChunk(it) }).takeIf { model.supportsStream })
                result.error?.let { throw FailoverHttpException(result.statusCode, it) }
                require(result.content.isNotBlank()) { "模型返回了空回复" }
                if (!hadThinking && result.thinkingContent.isNotBlank()) relay.onReasoningChunk(result.thinkingContent)
                if (!hadContent) relay.onContentChunk(result.content)
                result
            } finally { relay.flush() }
        }
        val usage = usageMapToDetail(execution.value.usage)
        send(RealtimeEvent.Usage(inputTokens = usage.promptTokens, outputTokens = usage.completionTokens,
            model = execution.model.model, modelDisplayName = execution.model.name,
            cachedInputTokens = usage.cachedPromptTokens, cacheWriteTokens = usage.cacheWriteTokens,
            cacheReported = usage.cacheReported, modelId = execution.model.id))
    } catch (e: CancellationException) { throw e }
    catch (e: FailoverAllFailedException) { send(RealtimeEvent.Error(e.message ?: "角色队列中的模型全部不可用")) }
    send(RealtimeEvent.StreamEnd(null))
}.buffer(Channel.UNLIMITED)
