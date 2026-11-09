package io.github.jpbaking.smsgateway

import io.github.jpbaking.smsgateway.webhook.WebhookSigner
import org.junit.Assert.assertEquals
import org.junit.Test

class WebhookSignerTest {
    @Test
    fun signsTimestampDotBody() {
        // Expected value computed independently:
        // python3 -c "import hmac,hashlib;print(hmac.new(b'secret',b'1700000000.{\"a\":1}',hashlib.sha256).hexdigest())"
        assertEquals(
            "sha256=49f24e537407743fa4a0242bb63b94b9a47ee99cbbe071ccd8a22550ae411686",
            WebhookSigner.sign("secret", 1_700_000_000L, """{"a":1}"""),
        )
    }
}
