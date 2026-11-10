package io.github.jpbaking.smsgateway.server

import io.github.jpbaking.smsgateway.data.MessageEntity
import io.github.jpbaking.smsgateway.data.SecretBox
import io.github.jpbaking.smsgateway.data.WebhookEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val apiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

@Serializable
data class SendRequest(
    val to: String,
    val body: String,
    /** Zero-based SIM slot (0 = SIM 1). */
    val simSlot: Int? = null,
    /** Android subscription id, from `GET /api/v1/sims`. Takes precedence over [simSlot]. */
    val subscriptionId: Int? = null,
)

@Serializable
data class MessageDto(
    val id: String,
    val direction: String,
    val phoneNumber: String,
    val body: String,
    val subscriptionId: Int?,
    val simSlot: Int?,
    val status: String,
    val parts: Int,
    val partsSent: Int,
    val partsDelivered: Int,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Outgoing messages only: the API key that sent it. */
    val apiKeyId: String?,
    val apiKeyName: String?,
) {
    companion object {
        fun from(m: MessageEntity) = MessageDto(
            id = m.id,
            direction = m.direction.name.lowercase(),
            phoneNumber = m.phoneNumber,
            body = m.body,
            subscriptionId = m.subscriptionId.takeIf { it >= 0 },
            simSlot = m.simSlot.takeIf { it >= 0 },
            status = m.status.name.lowercase(),
            parts = m.partCount,
            partsSent = m.partsSent,
            partsDelivered = m.partsDelivered,
            error = m.error,
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
            apiKeyId = m.apiKeyId,
            apiKeyName = m.apiKeyName,
        )
    }
}

@Serializable
data class MessageList(val items: List<MessageDto>, val limit: Int, val offset: Int)

@Serializable
data class HeaderInput(val key: String, val value: String, val isSecret: Boolean = false)

@Serializable
data class CreateWebhookRequest(
    val url: String,
    /** Defaults to every event. */
    val events: List<String>? = null,
    /** Generated if omitted. */
    val secret: String? = null,
    /** Extra headers sent with every delivery. */
    val headers: List<HeaderInput>? = null,
    /** Skip TLS certificate checks for this webhook (self-signed dev/test servers). */
    val insecure: Boolean = false,
)

@Serializable
data class HeaderDto(val key: String, val value: String, val isSecret: Boolean)

@Serializable
data class WebhookDto(
    val id: String,
    val url: String,
    val events: List<String>,
    /** The signing secret. Only returned when the webhook is created. */
    val secret: String?,
    /** Secret header values are masked. */
    val headers: List<HeaderDto>,
    /** TLS certificate checks are skipped for this webhook. */
    val insecure: Boolean,
    val createdAt: Long,
) {
    companion object {
        const val MASK = "********"

        fun from(w: WebhookEntity, revealSecret: Boolean = false) = WebhookDto(
            id = w.id,
            url = w.url,
            events = w.events.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            secret = if (revealSecret) SecretBox.decrypt(w.secret) else null,
            headers = w.storedHeaders().map {
                HeaderDto(it.key, if (it.isSecret) MASK else it.value, it.isSecret)
            },
            insecure = w.insecure,
            createdAt = w.createdAt,
        )
    }
}

@Serializable
data class WebhookPayload(val id: String, val event: String, val timestamp: Long, val message: MessageDto)

@Serializable
data class ErrorDto(val error: String)

@Serializable
data class HealthDto(val status: String, val version: String)
