package io.github.jpbaking.smsgateway

import io.github.jpbaking.smsgateway.data.EventEntity
import io.github.jpbaking.smsgateway.server.EventStream
import io.github.jpbaking.smsgateway.server.EventStream.Start
import org.junit.Assert.assertEquals
import org.junit.Test

class EventStreamTest {

    @Test
    fun withoutLastEventIdOnlyNewEventsAreSent() {
        assertEquals(Start(42, resync = false), EventStream.resolveStart(null, oldest = 1, newest = 42))
        assertEquals(Start(0, resync = false), EventStream.resolveStart(null, oldest = null, newest = 0))
    }

    @Test
    fun lastEventIdReplaysWhatCameAfterIt() {
        assertEquals(Start(40, resync = false), EventStream.resolveStart(40, oldest = 1, newest = 42))
        assertEquals(Start(42, resync = false), EventStream.resolveStart(42, oldest = 1, newest = 42))
        // The next event after the client's last one is still stored.
        assertEquals(Start(9, resync = false), EventStream.resolveStart(9, oldest = 10, newest = 42))
    }

    @Test
    fun cleanedUpEventsAskTheClientToResync() {
        assertEquals(Start(5, resync = true), EventStream.resolveStart(5, oldest = 10, newest = 42))
    }

    @Test
    fun anIdNewerThanAnythingStoredAsksTheClientToResync() {
        // E.g. the app's data was cleared, so ids started again.
        assertEquals(Start(3, resync = true), EventStream.resolveStart(500, oldest = 1, newest = 3))
        assertEquals(Start(0, resync = true), EventStream.resolveStart(7, oldest = null, newest = 0))
    }

    @Test
    fun framesFollowTheSseFormat() {
        val e = EventEntity(seq = 7, event = "sms:received", payload = """{"id":"x"}""", createdAt = 0)
        assertEquals("id: 7\nevent: sms:received\ndata: {\"id\":\"x\"}\n\n", EventStream.frame(e))
        assertEquals("event: resync\ndata: {\"lastEventId\":5}\n\n", EventStream.resyncFrame(5))
    }
}
