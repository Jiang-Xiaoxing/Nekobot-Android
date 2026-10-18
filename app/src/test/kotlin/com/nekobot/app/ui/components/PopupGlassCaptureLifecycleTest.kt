package com.nekobot.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupGlassCaptureLifecycleTest {
    @Test
    fun acquireAfterReleaseMakesOldTicketStale() {
        val lifecycle = PopupGlassCaptureLifecycle()
        lifecycle.acquire()
        val oldTicket = lifecycle.release()!!

        lifecycle.acquire()

        assertTrue(lifecycle.finishRelease(oldTicket))
        assertEquals(1, lifecycle.consumerCount)
        assertTrue(lifecycle.captureEnabled)
    }

    @Test
    fun captureRemainsEnabledUntilBothConsumersRelease() {
        val lifecycle = PopupGlassCaptureLifecycle()
        lifecycle.acquire()
        lifecycle.acquire()

        assertNull(lifecycle.release())
        assertEquals(1, lifecycle.consumerCount)
        assertTrue(lifecycle.captureEnabled)

        val lastTicket = lifecycle.release()!!
        assertEquals(0, lifecycle.consumerCount)
        assertFalse(lifecycle.finishRelease(lastTicket))
        assertFalse(lifecycle.captureEnabled)
    }

    @Test
    fun lastConsumerReleaseFinishesByDisablingCapture() {
        val lifecycle = PopupGlassCaptureLifecycle()
        lifecycle.acquire()

        val ticket = lifecycle.release()!!

        assertFalse(lifecycle.finishRelease(ticket))
        assertFalse(lifecycle.captureEnabled)
    }

    @Test
    fun repeatedPopupHandoffsKeepOnlyLatestReleaseTicketValid() {
        val lifecycle = PopupGlassCaptureLifecycle()
        var latestTicket: Long? = null

        repeat(20) {
            lifecycle.acquire()
            latestTicket?.let { staleTicket ->
                assertTrue(lifecycle.finishRelease(staleTicket))
                assertTrue(lifecycle.captureEnabled)
            }
            latestTicket = lifecycle.release()
            assertTrue(lifecycle.captureEnabled)
        }

        assertFalse(lifecycle.finishRelease(latestTicket!!))
        assertFalse(lifecycle.captureEnabled)
        assertEquals(0, lifecycle.consumerCount)
    }

    @Test
    fun duplicateReleaseDoesNotCreateAnotherTicketOrChangeState() {
        val lifecycle = PopupGlassCaptureLifecycle()
        lifecycle.acquire()
        val ticket = lifecycle.release()

        assertNull(lifecycle.release())
        assertEquals(0, lifecycle.consumerCount)
        assertFalse(lifecycle.finishRelease(ticket!!))
        assertFalse(lifecycle.captureEnabled)
    }
}
