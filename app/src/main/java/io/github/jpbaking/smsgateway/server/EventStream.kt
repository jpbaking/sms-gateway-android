package io.github.jpbaking.smsgateway.server

import io.github.jpbaking.smsgateway.data.EventDao
import io.github.jpbaking.smsgateway.data.EventEntity
import io.github.jpbaking.smsgateway.data.Settings
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The optional Server-Sent Events stream at `GET /api/v1/events`.
 *
 * While it's on, every event that webhooks get is also stored in the `events` table, with an
 * increasing id. A client that drops (Wi-Fi change, tunnel restart) reconnects with
 * `Last-Event-ID` and gets what it missed from the database, then live events. A comment line is
 * sent every [HEARTBEAT_MS] so proxies such as Cloudflare (which cuts idle connections at about
 * 100 seconds) keep the connection open.
 *
 * A stream belongs to the API key that opened it. The key is only checked when the stream opens,
 * so [closeStreamsOf] ends a key's streams when the key is disabled, revoked or regenerated, or
 * loses the `events` scope.
 */
class EventStream(private val dao: EventDao, private val settings: Settings) {

    private val _enabled = MutableStateFlow(settings.eventStreamEnabled)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** The newest stored event id; streams wait on it for new events. */
    private val latest = MutableStateFlow(0L)

    private val _clients = MutableStateFlow(0)

    /** How many clients are connected right now. */
    val clients: StateFlow<Int> = _clients.asStateFlow()

    /** Bumped for a key by [closeStreamsOf]; a stream ends when its key's number has moved on. */
    private val keyEpochs = ConcurrentHashMap<String, Long>()

    /** Changes whenever any key's epoch does, to wake the streams that are waiting. */
    private val epochChanges = MutableStateFlow(0L)

    private fun epochOf(keyId: String): Long = keyEpochs[keyId] ?: 0L

    /** Ends every open stream that [keyId] opened, within a moment. */
    @Synchronized
    fun closeStreamsOf(keyId: String) {
        keyEpochs[keyId] = epochOf(keyId) + 1
        epochChanges.update { it + 1 }
    }

    /** Turning it off also ends open streams within a moment. */
    fun setEnabled(on: Boolean) {
        settings.eventStreamEnabled = on
        _enabled.value = on
    }

    /** Stores an event for the stream. Does nothing while the stream is off. */
    suspend fun record(event: String, payload: String) {
        if (!_enabled.value) return
        val seq = dao.insert(EventEntity(event = event, payload = payload, createdAt = System.currentTimeMillis()))
        latest.update { maxOf(it, seq) }
    }

    /**
     * Writes the stream to [out] until the client goes away (the write fails), the stream is
     * turned off, the key's streams are closed with [closeStreamsOf], or the server stops.
     *
     * @param lastEventId the client's `Last-Event-ID`, or null to start with new events only.
     * @param events wire names to send, or null for all.
     * @param keyId the API key that opened the stream.
     * @param stillAllowed asked once the stream is registered under [keyId], to catch a key that
     *   was disabled or revoked between the request's own check and this point.
     */
    suspend fun serve(
        out: ByteWriteChannel,
        lastEventId: Long?,
        events: Set<String>?,
        keyId: String,
        stillAllowed: suspend () -> Boolean = { true },
    ) {
        val epoch = epochOf(keyId)
        if (!stillAllowed()) return
        fun open() = _enabled.value && epochOf(keyId) == epoch
        _clients.update { it + 1 }
        try {
            val newest = dao.maxSeq() ?: 0L
            latest.update { maxOf(it, newest) }
            val start = resolveStart(lastEventId, oldest = dao.minSeq(), newest = newest)
            var cursor = start.cursor

            out.writeStringUtf8("retry: $RETRY_MS\n\n")
            if (start.resync) out.writeStringUtf8(resyncFrame(lastEventId))
            out.flush()
            var lastWrite = System.currentTimeMillis()

            while (open()) {
                val batch = dao.after(cursor, BATCH)
                var wrote = false
                for (e in batch) {
                    cursor = e.seq
                    if (events == null || e.event in events) {
                        out.writeStringUtf8(frame(e))
                        wrote = true
                    }
                }
                if (wrote) {
                    out.flush()
                    lastWrite = System.currentTimeMillis()
                }
                if (batch.size == BATCH) continue

                val wait = HEARTBEAT_MS - (System.currentTimeMillis() - lastWrite)
                val woke = wait > 0 && withTimeoutOrNull(wait) {
                    val after = cursor
                    combine(latest, _enabled, epochChanges) { seq, _, _ -> seq > after || !open() }.first { it }
                } != null
                if (!woke) {
                    out.writeStringUtf8(": ping\n\n")
                    out.flush()
                    lastWrite = System.currentTimeMillis()
                }
            }
        } finally {
            _clients.update { it - 1 }
        }
    }

    /** Deletes events past the message retention period, and keeps at most [MAX_KEPT]. */
    suspend fun cleanup(retentionDays: Int): Int {
        val old = if (retentionDays > 0) {
            dao.deleteOlderThan(System.currentTimeMillis() - retentionDays * 86_400_000L)
        } else {
            0
        }
        return old + dao.trimTo(MAX_KEPT)
    }

    /** Where a new stream starts. [resync] means the client may have missed events. */
    data class Start(val cursor: Long, val resync: Boolean)

    companion object {
        const val HEARTBEAT_MS = 30_000L
        const val RETRY_MS = 5_000
        const val MAX_KEPT = 10_000
        private const val BATCH = 200

        /**
         * Without [lastEventId], the client only gets new events. With one, it gets everything
         * after it, unless those events are gone ([oldest] is past it, after cleanup) or the id
         * is newer than anything stored (the app's data was cleared), in which case the client
         * is told to [Start.resync].
         */
        fun resolveStart(lastEventId: Long?, oldest: Long?, newest: Long): Start = when {
            lastEventId == null -> Start(newest, resync = false)
            lastEventId > newest -> Start(newest, resync = true)
            oldest != null && lastEventId < oldest - 1 -> Start(lastEventId, resync = true)
            else -> Start(lastEventId, resync = false)
        }

        /** One SSE event. The payload is single-line JSON, so one `data:` line is enough. */
        fun frame(e: EventEntity): String = "id: ${e.seq}\nevent: ${e.event}\ndata: ${e.payload}\n\n"

        fun resyncFrame(lastEventId: Long?): String =
            "event: resync\ndata: {\"lastEventId\":${lastEventId ?: "null"}}\n\n"
    }
}
