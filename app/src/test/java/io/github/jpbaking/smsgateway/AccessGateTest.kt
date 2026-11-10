package io.github.jpbaking.smsgateway

import io.github.jpbaking.smsgateway.net.AccessPolicy
import io.github.jpbaking.smsgateway.net.ListenMode
import io.github.jpbaking.smsgateway.server.restrictCallers
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URI

/** The address check as the real server runs it: Ktor's CIO engine, a real connection. */
class AccessGateTest {
    private class Seen(var remote: InetAddress? = null, var local: InetAddress? = null, var calls: Int = 0)

    /** Starts a server behind [allows], asks it for `/`, and returns the status it answered. */
    private fun statusBehind(allows: (InetAddress?, InetAddress?) -> Boolean): Int {
        var reached = false
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            restrictCallers(allows)
            routing {
                get("/") {
                    reached = true
                    call.respondText("ok")
                }
            }
        }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val connection = URI("http://127.0.0.1:$port/").toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            val status = connection.responseCode
            assertEquals("The route runs only for callers that are let in", status == 200, reached)
            return status
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        }
    }

    @Test
    fun refusedCallersGet403BeforeAnyRouteRuns() {
        assertEquals(403, statusBehind { _, _ -> false })
    }

    @Test
    fun allowedCallersReachTheRoute() {
        assertEquals(200, statusBehind { _, _ -> true })
    }

    @Test
    fun bothAddressesOfTheConnectionAreRead() {
        val seen = Seen()
        statusBehind { remote, local ->
            seen.remote = remote
            seen.local = local
            seen.calls++
            true
        }
        assertEquals(1, seen.calls)
        assertTrue("remote was ${seen.remote}", seen.remote?.isLoopbackAddress == true)
        assertTrue("local was ${seen.local}", seen.local?.isLoopbackAddress == true)
    }

    @Test
    fun aRealPolicySeesThisMachineAsLoopback() {
        // Tunnel only lets in loopback and nothing else, so 200 here means the address was read right.
        assertEquals(200, statusBehind(AccessPolicy(ListenMode.TUNNEL_ONLY)::allows))
    }
}
