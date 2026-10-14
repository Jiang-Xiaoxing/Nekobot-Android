package com.nekobot.app.service

/** 同一槽位的多个任务只在首个开始/最后一个结束时变更服务占用。 */
internal class ForegroundTaskLeases {
    private val counts = mutableMapOf<String, Int>()
    @Synchronized fun acquire(key: String): Boolean {
        val count = (counts[key] ?: 0) + 1
        counts[key] = count
        return count == 1
    }
    @Synchronized fun release(key: String): Boolean {
        val count = counts[key] ?: return false
        if (count > 1) { counts[key] = count - 1; return false }
        counts.remove(key)
        return true
    }
    @Synchronized fun clear() { counts.clear() }
}
