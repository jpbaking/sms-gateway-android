package io.github.jpbaking.smsgateway.companion

import io.github.jpbaking.smsgateway.companion.api.Message
import io.github.jpbaking.smsgateway.companion.api.Sim
import io.github.jpbaking.smsgateway.companion.sync.Conversations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationsTest {
    private fun msg(
        id: String,
        number: String = "+639171234567",
        incoming: Boolean = true,
        at: Long = 0,
        sub: Int? = null,
        slot: Int? = null,
    ) = Message(
        id = id,
        direction = if (incoming) "in" else "out",
        phoneNumber = number,
        body = "hi",
        subscriptionId = sub,
        simSlot = slot,
        status = if (incoming) "received" else "sent",
        createdAt = at,
        updatedAt = at,
    )

    private val sim1 = Sim(subscriptionId = 1, slot = 0, displayName = "Work")
    private val sim2 = Sim(subscriptionId = 2, slot = 1, displayName = "Personal", isDefaultSms = true)

    @Test
    fun `one number written different ways is one conversation`() {
        val keys = listOf("+639171234567", "09171234567", "0917 123 4567", "+63 917-123-4567").map { Conversations.key(it) }
        assertEquals(1, keys.toSet().size)
    }

    @Test
    fun `short codes and sender ids keep their own keys`() {
        assertEquals("2600", Conversations.key("2600"))
        assertEquals("gcash", Conversations.key("GCash"))
        assertNotEquals(Conversations.key("2600"), Conversations.key("26000"))
    }

    @Test
    fun `normalized form wins when the platform provides one`() {
        // A German number with a 9-digit national part: only the E.164 form lines up.
        assertEquals(Conversations.key("+49301234567"), Conversations.key("0301234567", normalized = "+49301234567"))
    }

    @Test
    fun `groups newest conversation first and messages newest first`() {
        val list = Conversations.group(
            listOf(
                msg("a", "+639171234567", at = 1),
                msg("b", "+15551234567", at = 5),
                msg("c", "09171234567", incoming = false, at = 9),
            ),
        ) { Conversations.key(it) }
        assertEquals(listOf("c", "a"), list[0].messages.map { it.id })
        assertEquals("09171234567", list[0].address)
        assertEquals("b", list[1].latest.id)
    }

    @Test
    fun `reply uses the SIM of the newest received message`() {
        val messages = listOf(
            msg("old-in", at = 1, sub = 1, slot = 0),
            msg("new-in", at = 5, sub = 2, slot = 1),
            msg("newer-out", incoming = false, at = 9, sub = 1, slot = 0),
        )
        assertEquals(sim2, Conversations.replySim(messages, listOf(sim1, sim2)))
    }

    @Test
    fun `reply falls back to the last SIM sent from, then the default`() {
        val outOnly = listOf(msg("out", incoming = false, at = 3, sub = 1, slot = 0))
        assertEquals(sim1, Conversations.replySim(outOnly, listOf(sim1, sim2)))
        assertEquals(sim2, Conversations.replySim(emptyList(), listOf(sim1, sim2)))
        assertNull(Conversations.replySim(outOnly, emptyList()))
    }

    @Test
    fun `a SIM that is gone is matched by slot, or skipped`() {
        // Subscription 7 no longer exists, but the message came in on slot 1.
        assertEquals(sim2, Conversations.replySim(listOf(msg("in", at = 1, sub = 7, slot = 1)), listOf(sim1, sim2)))
        // Neither exists: next candidate, the older received message.
        val messages = listOf(msg("new", at = 5, sub = 7, slot = 4), msg("old", at = 1, sub = 1, slot = 0))
        assertEquals(sim1, Conversations.replySim(messages, listOf(sim1, sim2)))
    }

    @Test
    fun `unread counts received messages after the read mark`() {
        val c = Conversations.group(
            listOf(msg("a", at = 1), msg("b", at = 5), msg("c", incoming = false, at = 9)),
        ) { Conversations.key(it) }.single()
        assertEquals(1, Conversations.unreadCount(c, readUpTo = 1))
        assertEquals(0, Conversations.unreadCount(c, readUpTo = 5))
    }
}
