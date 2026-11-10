package io.github.jpbaking.smsgateway.companion

import io.github.jpbaking.smsgateway.companion.api.GatewayConfig
import io.github.jpbaking.smsgateway.companion.sync.GatewaySync
import io.github.jpbaking.smsgateway.companion.sync.LinkState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs [GatewaySync] against a fake gateway. */
class GatewaySyncTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    private fun message(id: String, status: String, updatedAt: Long, direction: String = "out") =
        """{"id":"$id","direction":"$direction","phoneNumber":"+15551234567","body":"hi","subscriptionId":1,"simSlot":0,""" +
            """"status":"$status","parts":1,"partsSent":0,"partsDelivered":0,"error":null,"createdAt":1,"updatedAt":$updatedAt}"""

    private var eventsResponse: () -> MockResponse = { MockResponse().setResponseCode(404) }

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.getHeader("X-API-Key") != "k") return MockResponse().setResponseCode(401).setBody("""{"error":"Invalid API key"}""")
                val path = request.path.orEmpty()
                return when {
                    path == "/api/v1/sims" -> MockResponse().setBody(
                        """[{"subscriptionId":1,"slot":0,"displayName":"Work","carrierName":"Globe","number":null,"isDefaultSms":true}]""",
                    )
                    path.startsWith("/api/v1/messages") -> MockResponse().setBody(
                        """{"items":[${message("m1", "sending", 2)}],"limit":500,"offset":0}""",
                    )
                    path == "/api/v1/events" -> eventsResponse()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun sync(key: String = "k") =
        GatewaySync(MutableStateFlow(GatewayConfig(server.url("/").toString().trimEnd('/'), key, null))) {}

    @Test
    fun `loads history when the stream opens, then applies events`() = runBlocking {
        eventsResponse = {
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "retry: 5000\n\n" +
                        "id: 7\nevent: sms:sent\ndata: {\"id\":\"e1\",\"event\":\"sms:sent\",\"message\":${message("m1", "sent", 3)}}\n\n" +
                        ": ping\n\n",
                )
        }
        val sync = sync()
        val job = launch(Dispatchers.Default) { sync.runWhileVisible() }
        withTimeout(5_000) { sync.messages.first { list -> list.any { it.id == "m1" && it.status == "sent" } } }
        assertEquals(1, sync.sims.value.size)
        assertTrue(sync.loaded.value)
        job.cancelAndJoin()
    }

    @Test
    fun `reconnects with the last event id`() = runBlocking {
        eventsResponse = {
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("id: 41\nevent: sms:delivered\ndata: {\"message\":${message("m1", "delivered", 9)}}\n\n")
        }
        val sync = sync()
        val job = launch(Dispatchers.Default) { sync.runWhileVisible() }
        // The body ends, so the stream closes and the app reconnects 5 s later.
        withTimeout(10_000) {
            while (requests.count { it.path == "/api/v1/events" } < 2) kotlinx.coroutines.delay(100)
        }
        job.cancelAndJoin()
        val second = requests.filter { it.path == "/api/v1/events" }[1]
        assertEquals("41", second.getHeader("Last-Event-ID"))
    }

    @Test
    fun `falls back to polling while the stream is off`() = runBlocking {
        val sync = sync()
        val job = launch(Dispatchers.Default) { sync.runWhileVisible() }
        withTimeout(5_000) { sync.state.first { it == LinkState.Polling } }
        assertEquals(listOf("m1"), sync.messages.value.map { it.id })
        job.cancelAndJoin()
    }

    @Test
    fun `falls back to polling when the key may not follow the stream`() = runBlocking {
        eventsResponse = {
            MockResponse().setResponseCode(403).setBody("""{"error":"needs the 'events' scope","scope":"events"}""")
        }
        val sync = sync()
        val job = launch(Dispatchers.Default) { sync.runWhileVisible() }
        withTimeout(5_000) { sync.state.first { it == LinkState.Polling } }
        assertEquals(listOf("m1"), sync.messages.value.map { it.id })
        job.cancelAndJoin()
    }

    @Test
    fun `reports a rejected API key`() = runBlocking {
        val sync = sync(key = "wrong")
        val job = launch(Dispatchers.Default) { sync.runWhileVisible() }
        val state = withTimeout(5_000) { sync.state.first { it is LinkState.Offline } } as LinkState.Offline
        assertTrue(state.authFailed)
        job.cancelAndJoin()
    }
}
