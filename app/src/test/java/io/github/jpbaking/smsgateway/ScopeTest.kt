package io.github.jpbaking.smsgateway

import io.github.jpbaking.smsgateway.data.ApiKeyEntity
import io.github.jpbaking.smsgateway.data.Scope
import io.github.jpbaking.smsgateway.data.ShortCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeTest {
    private fun key(scopes: String) =
        ApiKeyEntity(id = "id", name = "n", keyHash = "h", keyPrefix = "p", createdAt = 0, scopes = scopes)

    @Test
    fun scopesRoundTripInAFixedOrder() {
        val stored = Scope.format(setOf(Scope.WEBHOOKS, Scope.SEND))
        assertEquals("send,webhooks", stored)
        assertEquals(setOf(Scope.SEND, Scope.WEBHOOKS), Scope.parse(stored))
        assertEquals("send,read,events,webhooks,send:shortcode", Scope.format(Scope.ALL))
    }

    @Test
    fun unknownAndEmptyNamesAreIgnored() {
        assertEquals(setOf(Scope.READ), Scope.parse(" read , future-scope,,"))
        assertEquals(emptySet<Scope>(), Scope.parse(""))
    }

    @Test
    fun aNewKeyCanSendAndReadButNothingElse() {
        val fresh = ApiKeyEntity(id = "id", name = "n", keyHash = "h", keyPrefix = "p", createdAt = 0)
        assertEquals(setOf(Scope.SEND, Scope.READ), fresh.scopeSet())
        assertTrue(fresh.enabled)
        assertFalse(fresh.needsReview)
        assertFalse(fresh.has(Scope.WEBHOOKS))
        assertFalse(fresh.has(Scope.EVENTS))
        assertFalse(fresh.has(Scope.SEND_SHORTCODE))
    }

    @Test
    fun hasChecksTheStoredScopes() {
        assertTrue(key("send,events").has(Scope.EVENTS))
        assertFalse(key("send,events").has(Scope.READ))
        // "send" must not be mistaken for "send:shortcode", or the other way round.
        assertFalse(key("send").has(Scope.SEND_SHORTCODE))
        assertFalse(key("send:shortcode").has(Scope.SEND))
    }

    @Test
    fun shortCodesAreSixDigitsOrFewer() {
        listOf("2366", "123456", " 8080 ", "21-21").forEach { assertTrue(it, ShortCodes.isShortCode(it)) }
        listOf("1234567", "09171234567", "+639171234567", "+12345", "", "abc").forEach {
            assertFalse(it, ShortCodes.isShortCode(it))
        }
    }
}
