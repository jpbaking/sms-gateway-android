package io.github.jpbaking.smsgateway

import io.github.jpbaking.smsgateway.data.ApiKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeysTest {
    @Test
    fun newKeysHaveThePrefixAndAreUnique() {
        val a = ApiKeys.newKey()
        val b = ApiKeys.newKey()
        assertTrue(a.matches(Regex("^sgw_[0-9a-f]{48}$")))
        assertNotEquals(a, b)
    }

    @Test
    fun prefixShowsTheFirstEightCharactersAfterSgw() {
        assertEquals("sgw_1a2b3c4d", ApiKeys.prefixOf("sgw_1a2b3c4d5e6f"))
        assertEquals("legacyke", ApiKeys.prefixOf("legacykey123"))
    }

    @Test
    fun hashIsSha256Hex() {
        // printf abc | sha256sum
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ApiKeys.hash("abc"))
    }
}
