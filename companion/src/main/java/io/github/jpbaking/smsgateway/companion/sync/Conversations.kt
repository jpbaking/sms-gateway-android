package io.github.jpbaking.smsgateway.companion.sync

import io.github.jpbaking.smsgateway.companion.api.Message
import io.github.jpbaking.smsgateway.companion.api.Sim

/** All messages exchanged with one number, newest message first in [messages]. */
data class Conversation(
    val key: String,
    /** The number as the newest message has it; replies go here. */
    val address: String,
    val messages: List<Message>,
) {
    val latest: Message get() = messages.first()
}

object Conversations {
    /**
     * A key that is the same for the different ways one number gets written, so "+639171234567",
     * "09171234567" and "0917 123 4567" land in one conversation, like in a normal SMS app.
     *
     * [normalized] is the number in E.164 form when the platform could work it out (it knows the
     * country), or null. Numbers compare on their last 10 digits; shorter ones (short codes)
     * compare whole. Alphanumeric sender ids ("GCASH") compare case-insensitively.
     */
    fun key(address: String, normalized: String? = null): String {
        val source = normalized ?: address
        if (address.any { it.isLetter() }) return address.trim().lowercase()
        val digits = source.filter { it.isDigit() }
        return when {
            digits.isEmpty() -> address.trim()
            digits.length > 10 -> digits.takeLast(10)
            else -> digits
        }
    }

    /** Groups messages into conversations, the most recently active first. */
    fun group(messages: Collection<Message>, keyOf: (String) -> String): List<Conversation> =
        messages
            .groupBy { keyOf(it.phoneNumber) }
            .map { (key, list) ->
                val sorted = list.sortedWith(NEWEST_FIRST)
                Conversation(key, sorted.first().phoneNumber, sorted)
            }
            .sortedWith(compareByDescending<Conversation> { it.latest.createdAt }.thenBy { it.key })

    val NEWEST_FIRST: Comparator<Message> =
        compareByDescending<Message> { it.createdAt }.thenByDescending { it.id }

    /**
     * The SIM to reply from: the one the newest *received* message came in on, so the other side
     * sees the reply from the number they texted. Without a received message, the SIM last sent
     * from. Falls back to the gateway's default SMS SIM, then the first SIM. Returns null when the
     * gateway reported no SIMs, and the gateway then picks.
     */
    fun replySim(messages: List<Message>, sims: List<Sim>): Sim? {
        if (sims.isEmpty()) return null
        val sorted = messages.sortedWith(NEWEST_FIRST)
        val candidates = sorted.filter { it.incoming } + sorted.filter { !it.incoming }
        for (m in candidates) {
            match(m, sims)?.let { return it }
        }
        return sims.firstOrNull { it.isDefaultSms } ?: sims.first()
    }

    /** The SIM a message used: by subscription id first (survives slot swaps), then by slot. */
    fun match(m: Message, sims: List<Sim>): Sim? =
        m.subscriptionId?.let { id -> sims.firstOrNull { it.subscriptionId == id } }
            ?: m.simSlot?.let { slot -> sims.firstOrNull { it.slot == slot } }

    /** Received messages newer than [readUpTo] count as unread. */
    fun unreadCount(c: Conversation, readUpTo: Long): Int =
        c.messages.count { it.incoming && it.createdAt > readUpTo }
}
