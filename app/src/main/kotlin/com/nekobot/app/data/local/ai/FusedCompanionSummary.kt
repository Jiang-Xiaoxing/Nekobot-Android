package com.nekobot.app.data.local.ai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 同一次总结记忆等待中的顺序执行；每包重新读取原话和回执，失败或停止立即向外传播。 */
internal object FusedCompanionSummary {
    suspend fun run(
        nextPlan: suspend (continueThroughMessageId: String?) -> FusedCompanionPolicy.WindowPlan?,
        summarize: suspend (FusedCompanionPolicy.WindowPlan) -> Int
    ): Int {
        val seen = mutableSetOf<String>()
        var continueThrough: String? = null
        var changed = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val plan = nextPlan(continueThrough) ?: break
            if (!plan.needsSummary) break
            require(plan.batch.isNotEmpty()) { FusedMemoryText.TURN_TOO_LARGE.value() }
            val key = CompanionMemoryPolicy.digest(plan.batch.joinToString { CompanionMemoryPolicy.fingerprint(it) })
            require(seen.add(key)) { FusedMemoryText.RETRY_CHANGED.value() }
            currentCoroutineContext().ensureActive()
            changed += summarize(plan)
            continueThrough = plan.continueThroughMessageId
        }
        return changed
    }
}
