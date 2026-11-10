package io.github.jpbaking.smsgateway

import io.github.jpbaking.smsgateway.data.Settings
import io.github.jpbaking.smsgateway.data.TunnelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules in [Settings] that don't need a phone: retry limits, and when the tunnel counts as set up. */
class SettingsRulesTest {

    @Test
    fun retriesNeverGoOnForever() {
        // 0 was "never give up", and the default. It now means the default.
        assertEquals(72, Settings.retryMaxHours(0))
        assertEquals(72, Settings.retryMaxHours(-5))
    }

    @Test
    fun retryHoursAreKeptWithinThirtyDays() {
        assertEquals(1, Settings.retryMaxHours(1))
        assertEquals(48, Settings.retryMaxHours(48))
        assertEquals(720, Settings.retryMaxHours(720))
        assertEquals(720, Settings.retryMaxHours(721))
        assertEquals(720, Settings.retryMaxHours(Int.MAX_VALUE))
    }

    @Test
    fun retryLimitFitsInMilliseconds() {
        // WebhookWorker multiplies the hours by 3 600 000 as a Long.
        assertEquals(2_592_000_000L, Settings.MAX_RETRY_MAX_HOURS * 3_600_000L)
    }

    @Test
    fun tunnelIsReadyOnlyWhenOnAndWithAToken() {
        assertTrue(Settings.tunnelReady(enabled = true, mode = TunnelMode.TOKEN, hasToken = true))
        assertFalse(Settings.tunnelReady(enabled = false, mode = TunnelMode.TOKEN, hasToken = true))
        assertFalse(Settings.tunnelReady(enabled = true, mode = TunnelMode.TOKEN, hasToken = false))
        assertFalse(Settings.tunnelReady(enabled = false, mode = TunnelMode.TOKEN, hasToken = false))
    }

    @Test
    fun quickTunnelCountsOnlyWhereQuickTunnelsAreOffered() {
        assertFalse(Settings.tunnelReady(enabled = true, mode = TunnelMode.QUICK, hasToken = true, quickTunnels = false))
        assertTrue(Settings.tunnelReady(enabled = true, mode = TunnelMode.QUICK, hasToken = false, quickTunnels = true))
        assertFalse(Settings.tunnelReady(enabled = false, mode = TunnelMode.QUICK, hasToken = false, quickTunnels = true))
    }

    @Test
    fun quickTunnelsAreOffInThisBuild() {
        assertFalse(Settings.tunnelReady(enabled = true, mode = TunnelMode.QUICK, hasToken = false))
    }
}
