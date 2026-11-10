package io.github.jpbaking.smsgateway.companion

import io.github.jpbaking.smsgateway.companion.api.Message
import io.github.jpbaking.smsgateway.companion.sync.MessageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStoreTest {
    private fun msg(status: String, updatedAt: Long) = Message(
        id = "m1", direction = "out", phoneNumber = "+15551234567", body = "hi",
        status = status, createdAt = 0, updatedAt = updatedAt,
    )

    @Test
    fun `newer status replaces older`() {
        val store = MessageStore()
        assertTrue(store.merge(listOf(msg("queued", 1))))
        assertTrue(store.merge(listOf(msg("sent", 2))))
        assertEquals("sent", store.snapshot().single().status)
    }

    @Test
    fun `a stale reload doesn't undo a newer event`() {
        val store = MessageStore()
        store.merge(listOf(msg("delivered", 5)))
        assertFalse(store.merge(listOf(msg("sending", 3))))
        assertEquals("delivered", store.snapshot().single().status)
    }

    @Test
    fun `the same message again is not a change`() {
        val store = MessageStore()
        store.merge(listOf(msg("sent", 2)))
        assertFalse(store.merge(listOf(msg("sent", 2))))
    }
}
