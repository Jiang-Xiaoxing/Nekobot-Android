package com.nekobot.app.data.local

import com.nekobot.app.data.local.db.LocalMessageEntity
import com.nekobot.app.data.local.ai.estimateLocalTextTokens

/** Only complete, visible user/assistant pairs can survive a compaction. */
internal data class LongConversationSplit(
    val toSummarize: List<LocalMessageEntity>,
    val recent: List<LocalMessageEntity>
) {
    val boundaryId: String? get() = toSummarize.lastOrNull()?.id
    val recentEndId: String? get() = recent.lastOrNull()?.id
}

/**
 * The caller removes the protected in-flight user message first. Taking pairs from the end
 * keeps the summary boundary unambiguous even when imported messages share a timestamp.
 */
internal fun splitLongConversationHistory(
    completedMessages: List<LocalMessageEntity>,
    maxPairs: Int = 20,
    maxRecentTokens: Int
): LongConversationSplit {
    val eligible = completedMessages.filter { !it.role.equals("system", ignoreCase = true) }
    var start = eligible.size
    var pairs = 0
    var tokens = 0
    while (start >= 2 && pairs < maxPairs) {
        val reply = eligible[start - 1]
        val request = eligible[start - 2]
        if (!reply.role.equals("assistant", ignoreCase = true) ||
            !request.role.equals("user", ignoreCase = true)
        ) break
        val pairTokens = estimateLocalTextTokens(request.content) +
            estimateLocalTextTokens(reply.content) + 16
        if (tokens + pairTokens > maxRecentTokens) break
        tokens += pairTokens
        start -= 2
        pairs++
    }
    return LongConversationSplit(eligible.take(start), eligible.drop(start))
}
