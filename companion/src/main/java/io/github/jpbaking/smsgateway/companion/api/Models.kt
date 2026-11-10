package io.github.jpbaking.smsgateway.companion.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val apiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** A message as the gateway returns it (see the gateway's docs/api.md, "The message object"). */
@Serializable
data class Message(
    val id: String,
    /** `in` or `out`. */
    val direction: String,
    /** The sender for incoming messages, the recipient for outgoing ones. */
    val phoneNumber: String,
    val body: String,
    val subscriptionId: Int? = null,
    val simSlot: Int? = null,
    /** queued, sending, sent, delivered, failed or received. */
    val status: String,
    val parts: Int = 1,
    val partsSent: Int = 0,
    val partsDelivered: Int = 0,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val apiKeyName: String? = null,
) {
    val incoming: Boolean get() = direction == "in"
}

@Serializable
data class MessagePage(val items: List<Message>, val limit: Int = 0, val offset: Int = 0)

@Serializable
data class Sim(
    val subscriptionId: Int,
    val slot: Int,
    val displayName: String? = null,
    val carrierName: String? = null,
    val number: String? = null,
    val isDefaultSms: Boolean = false,
) {
    /** "SIM 1", plus the SIM's name when it has one, e.g. "SIM 1 · Work". */
    val label: String
        get() {
            val name = displayName?.takeIf { it.isNotBlank() } ?: carrierName?.takeIf { it.isNotBlank() }
            return if (name == null) "SIM ${slot + 1}" else "SIM ${slot + 1} · $name"
        }
}

@Serializable
data class SendRequest(
    val to: String,
    val body: String,
    val simSlot: Int? = null,
    val subscriptionId: Int? = null,
)

/** The `data` of an `sms:*` event on the stream: the same body a webhook gets. */
@Serializable
data class EventPayload(val id: String? = null, val event: String? = null, val message: Message? = null)

@Serializable
data class Health(val status: String? = null, val version: String? = null)

@Serializable
data class ErrorBody(val error: String? = null)
