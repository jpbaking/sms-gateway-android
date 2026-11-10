package io.github.jpbaking.smsgateway

import io.github.jpbaking.smsgateway.webhook.WebhookWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class WebhookRetryTest {
    private val fiveMinutes = 300_000L

    @Test
    fun delaysDoubleThenCapAtTheConfiguredInterval() {
        val seconds = (0..7).map { WebhookWorker.nextDelayMillis(it, fiveMinutes) / 1000 }
        assertEquals(listOf(10L, 20L, 40L, 80L, 160L, 300L, 300L, 300L), seconds)
    }

    @Test
    fun hugeAttemptCountsDoNotOverflow() {
        assertEquals(fiveMinutes, WebhookWorker.nextDelayMillis(10_000, fiveMinutes))
    }

    @Test
    fun intervalBelowTheFirstStepStillWaitsTenSeconds() {
        assertEquals(10_000L, WebhookWorker.nextDelayMillis(3, 1_000L))
    }
}
