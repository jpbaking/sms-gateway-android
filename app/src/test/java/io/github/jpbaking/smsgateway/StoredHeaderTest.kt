package io.github.jpbaking.smsgateway

import io.github.jpbaking.smsgateway.data.StoredHeader
import org.junit.Assert.assertEquals
import org.junit.Test

class StoredHeaderTest {
    @Test
    fun roundTrips() {
        val headers = listOf(
            StoredHeader("Authorization", "enc:v1:abc", isSecret = true),
            StoredHeader("X-Tenant", "acme"),
        )
        assertEquals(headers, StoredHeader.decode(StoredHeader.encode(headers)))
    }

    @Test
    fun isSecretDefaultsToFalse() {
        assertEquals(listOf(StoredHeader("X-A", "1", false)), StoredHeader.decode("""[{"key":"X-A","value":"1"}]"""))
    }

    @Test
    fun badJsonReadsAsNoHeaders() {
        assertEquals(emptyList<StoredHeader>(), StoredHeader.decode("not json"))
    }
}
