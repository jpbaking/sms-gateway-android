package io.github.jpbaking.smsgateway.companion.sync

import io.github.jpbaking.smsgateway.companion.api.ApiException
import io.github.jpbaking.smsgateway.companion.api.EventPayload
import io.github.jpbaking.smsgateway.companion.api.GatewayClient
import io.github.jpbaking.smsgateway.companion.api.GatewayConfig
import io.github.jpbaking.smsgateway.companion.api.Message
import io.github.jpbaking.smsgateway.companion.api.SendRequest
import io.github.jpbaking.smsgateway.companion.api.Sim
import io.github.jpbaking.smsgateway.companion.api.StreamSignal
import io.github.jpbaking.smsgateway.companion.api.apiJson
import io.github.jpbaking.smsgateway.companion.api.isCertificateProblem
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How the app is connected to the gateway right now. */
sealed interface LinkState {
    /** No gateway URL or API key saved yet. */
    data object NotConfigured : LinkState

    data object Connecting : LinkState

    /** The event stream is open: changes show up as they happen. */
    data object Live : LinkState

    /** The gateway's event stream is turned off, so the app reloads every little while instead. */
    data object Polling : LinkState

    data class Offline(val message: String, val authFailed: Boolean = false, val certProblem: Boolean = false) : LinkState
}

/**
 * Keeps an in-memory copy of the gateway's messages and SIMs up to date while the app is in the
 * foreground ([runWhileVisible] is called for as long as the app is visible, and cancelled when
 * it isn't). There's no background service: while the app is hidden nothing is connected, and on
 * return the stream replays what was missed (`Last-Event-ID`), or the app reloads.
 */
class GatewaySync(private val config: StateFlow<GatewayConfig?>, private val onFirstLoad: (newestReceived: Long) -> Unit) {
    private val store = MessageStore()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _sims = MutableStateFlow<List<Sim>>(emptyList())
    val sims: StateFlow<List<Sim>> = _sims.asStateFlow()

    private val _state = MutableStateFlow<LinkState>(LinkState.NotConfigured)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /** True once messages were loaded at least once from the current gateway. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Whether the gateway may have older messages than the ones loaded. */
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val loadLock = Mutex()
    private var loadedFrom: GatewayConfig? = null
    private var loadedCount = 0

    @Volatile
    private var client: GatewayClient? = null

    @Volatile
    private var lastEventId: Long? = null

    suspend fun runWhileVisible() {
        config.collectLatest { cfg ->
            if (cfg == null) {
                client = null
                _state.value = LinkState.NotConfigured
                awaitCancellation()
            }
            if (loadedFrom?.baseUrl != cfg.baseUrl || loadedFrom?.apiKey != cfg.apiKey) forget()
            val c = GatewayClient(cfg)
            client = c
            stayConnected(c, cfg)
        }
    }

    private fun forget() {
        store.clear()
        lastEventId = null
        loadedCount = 0
        _messages.value = emptyList()
        _sims.value = emptyList()
        _loaded.value = false
        _hasMore.value = false
    }

    private suspend fun stayConnected(c: GatewayClient, cfg: GatewayConfig): Nothing {
        var failures = 0
        while (true) {
            if (_state.value !is LinkState.Live) _state.value = LinkState.Connecting
            try {
                c.events(lastEventId).collect { signal ->
                    when (signal) {
                        StreamSignal.Open -> {
                            _state.value = LinkState.Live
                            failures = 0
                            // Without an event id there's nothing to replay from, so load what's
                            // there. With one, the stream sends what was missed.
                            if (lastEventId == null || !_loaded.value) reload(c, cfg) else refreshSims(c)
                        }
                        is StreamSignal.Event -> handle(c, cfg, signal)
                    }
                }
                _state.value = LinkState.Offline("The gateway closed the connection")
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // 404: the stream is off on the gateway. 403: this key may not follow it. Either
                // way the messages are still there to load, so fall back to polling. (If 403 means
                // the gateway refuses this phone altogether, polling fails too and says so.)
                if (e.code == 404 || e.code == 403) {
                    pollWhileStreamIsOff(c, cfg)
                    continue
                }
                _state.value = LinkState.Offline(e.message.orEmpty(), authFailed = e.code == 401)
            } catch (e: Exception) {
                _state.value = offline(e)
            }
            failures++
            delay(retryDelay(failures))
        }
    }

    private suspend fun handle(c: GatewayClient, cfg: GatewayConfig, event: StreamSignal.Event) {
        if (event.type == "resync") {
            // The stream couldn't fill the gap. Start over from the message list, and from new
            // events only.
            lastEventId = null
            reload(c, cfg)
            return
        }
        val payload = runCatching { apiJson.decodeFromString(EventPayload.serializer(), event.data) }.getOrNull()
        payload?.message?.let { if (store.merge(listOf(it))) publish() }
        event.id?.toLongOrNull()?.let { lastEventId = it }
    }

    /** Reloads every 15 seconds, and tries the stream again every 2 minutes. */
    private suspend fun pollWhileStreamIsOff(c: GatewayClient, cfg: GatewayConfig) {
        repeat(8) {
            try {
                reload(c, cfg)
                _state.value = LinkState.Polling
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = offline(e)
            }
            delay(POLL_MS)
        }
    }

    private fun offline(e: Exception): LinkState.Offline = when {
        e is ApiException -> LinkState.Offline(e.message.orEmpty(), authFailed = e.code == 401)
        e.isCertificateProblem() -> LinkState.Offline("The gateway's certificate isn't trusted", certProblem = true)
        else -> LinkState.Offline(e.message ?: e.javaClass.simpleName)
    }

    private suspend fun reload(c: GatewayClient, cfg: GatewayConfig) = loadLock.withLock {
        refreshSims(c)
        val page = c.messages(PAGE, 0)
        store.merge(page.items)
        loadedCount = maxOf(loadedCount, page.items.size)
        if (!_loaded.value) _hasMore.value = page.items.size >= PAGE
        loadedFrom = cfg
        _loaded.value = true
        publish()
        onFirstLoad(page.items.filter { it.incoming }.maxOfOrNull { it.createdAt } ?: System.currentTimeMillis())
    }

    private suspend fun refreshSims(c: GatewayClient) {
        _sims.value = c.sims()
    }

    private fun publish() {
        _messages.value = store.snapshot()
    }

    private fun requireClient(): GatewayClient = client ?: throw IOException("Set up the gateway in Settings first")

    /** Pull to refresh. */
    suspend fun refresh() {
        val c = requireClient()
        reload(c, c.config)
    }

    /** Loads the next page of older messages. */
    suspend fun loadOlder() = loadLock.withLock {
        val c = requireClient()
        val page = c.messages(PAGE, loadedCount)
        loadedCount += page.items.size
        _hasMore.value = page.items.size >= PAGE
        if (store.merge(page.items)) publish()
    }

    /** Sends through the gateway. The queued message shows up right away; its status follows. */
    suspend fun send(to: String, body: String, sim: Sim?): Message {
        val c = requireClient()
        val sent = c.send(SendRequest(to = to, body = body, subscriptionId = sim?.subscriptionId, simSlot = sim?.slot))
        if (store.merge(listOf(sent))) publish()
        return sent
    }

    companion object {
        /** The gateway's maximum page size. */
        const val PAGE = 500
        const val POLL_MS = 15_000L

        /** 5 s, 10 s, 20 s, then 30 s between reconnects. */
        fun retryDelay(failures: Int): Long = when {
            failures <= 1 -> 5_000L
            failures == 2 -> 10_000L
            failures == 3 -> 20_000L
            else -> 30_000L
        }
    }
}
