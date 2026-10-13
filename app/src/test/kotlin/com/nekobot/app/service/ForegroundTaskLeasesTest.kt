package com.nekobot.app.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ForegroundTaskLeasesTest {
    @Test fun parallelAutomationTasksReleaseOnlyAfterTheLastTask() {
        val leases = ForegroundTaskLeases()
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val ready = CountDownLatch(20)
        val finish = CountDownLatch(1)
        val workers = (1..20).map {
            Thread {
                if (leases.acquire("automation")) starts.incrementAndGet()
                ready.countDown()
                finish.await()
                if (leases.release("automation")) stops.incrementAndGet()
            }.apply { start() }
        }
        ready.await()
        assertEquals(1, starts.get())
        assertEquals(0, stops.get())
        finish.countDown()
        workers.forEach { it.join() }
        assertEquals(1, stops.get())
        assertFalse(leases.release("automation"))
    }
}
