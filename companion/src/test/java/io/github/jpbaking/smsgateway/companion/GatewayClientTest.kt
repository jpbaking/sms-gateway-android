package io.github.jpbaking.smsgateway.companion

import io.github.jpbaking.smsgateway.companion.api.ApiException
import io.github.jpbaking.smsgateway.companion.api.GatewayClient
import io.github.jpbaking.smsgateway.companion.api.GatewayConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GatewayClientTest {
    private val gateway = MockWebServer()
    private val elsewhere = MockWebServer()

    @After
    fun stop() {
        gateway.shutdown()
        elsewhere.shutdown()
    }

    @Test
    fun `a redirect is an error and the API key goes nowhere else`(): Unit = runBlocking {
        elsewhere.enqueue(MockResponse().setBody("[]"))
        elsewhere.start()
        gateway.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/api/v1/sims").toString()),
        )
        gateway.start()
        val client = GatewayClient(GatewayConfig(gateway.url("/").toString().trimEnd('/'), "secret-key", null))

        try {
            client.sims()
            fail("A redirect must not be followed")
        } catch (e: ApiException) {
            assertEquals(302, e.code)
            assertTrue(e.message.orEmpty().contains("redirect"))
        }
        assertEquals("secret-key", gateway.takeRequest().getHeader("X-API-Key"))
        assertEquals(0, elsewhere.requestCount)
    }
}
