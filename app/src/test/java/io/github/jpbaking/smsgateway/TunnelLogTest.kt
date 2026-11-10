package io.github.jpbaking.smsgateway

import io.github.jpbaking.smsgateway.tunnel.LogEvent
import io.github.jpbaking.smsgateway.tunnel.TunnelLog
import io.github.jpbaking.smsgateway.tunnel.restartDelayMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunnelLogTest {
    @Test
    fun registeredConnection() {
        val line = "2026-11-10T03:00:00Z INF Registered tunnel connection connIndex=2 " +
            "connection=0a1b event=0 ip=198.41.200.13 location=sin01 protocol=quic"
        assertEquals(LogEvent.Registered(2), TunnelLog.parse(line))
    }

    @Test
    fun droppedConnections() {
        assertEquals(
            LogEvent.Dropped(1),
            TunnelLog.parse("2026-11-10T03:00:00Z ERR Connection terminated error=\"timeout\" connIndex=1"),
        )
        assertEquals(
            LogEvent.Dropped(0),
            TunnelLog.parse("2026-11-10T03:00:00Z INF Unregistered tunnel connection connIndex=0 event=0 ip=1.2.3.4"),
        )
        assertEquals(
            LogEvent.Dropped(3),
            TunnelLog.parse("2026-11-10T03:00:00Z INF Retrying connection in up to 4s connIndex=3 event=0"),
        )
    }

    @Test
    fun quickTunnelUrlInsideTheBox() {
        val line = "2026-11-10T03:00:00Z INF |  https://blue-river-test-1234.trycloudflare.com                    |"
        assertEquals(LogEvent.QuickTunnelUrl("https://blue-river-test-1234.trycloudflare.com"), TunnelLog.parse(line))
    }

    @Test
    fun apiHostIsNotTheQuickTunnelUrl() {
        assertNull(TunnelLog.parse("2026-11-10T03:00:00Z INF Requesting new quick Tunnel on https://api.trycloudflare.com..."))
    }

    @Test
    fun errorsAreReported() {
        assertEquals(
            LogEvent.Error("Provided Tunnel token is not valid."),
            TunnelLog.parse("2026-11-10T03:00:00Z ERR Provided Tunnel token is not valid."),
        )
        assertNull(TunnelLog.parse("2026-11-10T03:00:00Z INF Starting tunnel tunnelID=abc"))
    }

    @Test
    fun restartBackoffDoublesUpToAMinute() {
        assertEquals(listOf(2L, 2L, 4L, 8L, 16L, 32L, 60L, 60L), (0..7).map { restartDelayMillis(it) / 1000 })
    }
}
