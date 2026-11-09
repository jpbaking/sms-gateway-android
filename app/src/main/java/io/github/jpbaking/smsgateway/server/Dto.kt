package io.github.jpbaking.smsgateway.server

import io.github.jpbaking.smsgateway.data.MessageEntity
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
        )
    }
}

@Serializable
data class MessageList(val items: List<MessageDto>, val limit: Int, val offset: Int)

@Serializable
data class CreateWebhookRequest(
    val url: String,
    /** Defaults to every event. */
    val events: List<String>? = null,
    /** Generated if omitted. */
    val secret: String? = null,
)

@Serializable
data class WebhookDto(val id: String, val url: String, val events: List<String>, val secret: String, val createdAt: Long) {
    companion object {
        fun from(w: WebhookEntity) = WebhookDto(
            id = w.id,
            url = w.url,
            events = w.events.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            secret = w.secret,
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
