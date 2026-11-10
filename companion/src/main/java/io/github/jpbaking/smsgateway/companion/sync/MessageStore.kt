package io.github.jpbaking.smsgateway.companion.sync

import io.github.jpbaking.smsgateway.companion.api.Message

/**
 * Messages by id. A message that arrives again (from a reload, or as a status event) replaces the
 * copy held only if it isn't older, so a slow reload can't undo a newer status from the stream.
 */
class MessageStore {
    private val byId = LinkedHashMap<String, Message>()

    @Synchronized
    fun merge(messages: Collection<Message>): Boolean {
        var changed = false
        for (m in messages) {
            val old = byId[m.id]
            if (old == null || m.updatedAt >= old.updatedAt && m != old) {
                byId[m.id] = m
                changed = true
            }
        }
        return changed
    }

    @Synchronized
    fun clear() = byId.clear()

    @Synchronized
    fun snapshot(): List<Message> = byId.values.toList()

    @Synchronized
    fun size(): Int = byId.size
}
