package io.github.jpbaking.smsgateway.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class Direction { IN, OUT }

enum class MessageStatus {
    /** Stored, not yet handed to the radio. */
    QUEUED,

    /** Handed to SmsManager, waiting for the sent callback of every part. */
    SENDING,

    /** Every part was accepted by the network. */
    SENT,

    /** Every part got a delivery report back from the recipient's network. */
    DELIVERED,
    FAILED,

    /** Incoming message. */
    RECEIVED,
}

@Entity(
    tableName = "messages",
    indices = [Index("direction", "createdAt")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val direction: Direction,
    /** Recipient for outgoing messages, sender for incoming ones. */
    val phoneNumber: String,
    val body: String,
    /** Android subscription id of the SIM used, or -1 if unknown. */
    val subscriptionId: Int,
    /** Zero-based physical SIM slot, or -1 if unknown. */
    val simSlot: Int,
    val status: MessageStatus,
    val partCount: Int = 1,
    val partsSent: Int = 0,
    val partsDelivered: Int = 0,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** For outgoing messages: the API key that sent it. Kept even if the key is later revoked. */
    val apiKeyId: String? = null,
    val apiKeyName: String? = null,
)

@Entity(tableName = "webhooks")
data class WebhookEntity(
    @PrimaryKey val id: String,
    val url: String,
    /** Comma separated event names, see [io.github.jpbaking.smsgateway.webhook.WebhookEvent]. */
    val events: String,
    /** Shared secret used to HMAC-sign each payload, encrypted with [SecretBox]. */
    val secret: String,
    val createdAt: Long,
    /** JSON list of [StoredHeader], or null for none. Secret values are encrypted with [SecretBox]. */
    val headers: String? = null,
    /**
     * Skip TLS certificate and hostname checks when delivering to this webhook, for self-signed
     * dev/test servers. Only affects `https://` URLs.
     */
    @ColumnInfo(defaultValue = "0") val insecure: Boolean = false,
) {
    fun storedHeaders(): List<StoredHeader> = headers?.let { StoredHeader.decode(it) }.orEmpty()
}

/** An extra header sent with every delivery of a webhook. */
@Serializable
data class StoredHeader(val key: String, val value: String, val isSecret: Boolean = false) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(StoredHeader.serializer())

        fun encode(headers: List<StoredHeader>): String = json.encodeToString(serializer, headers)
        fun decode(stored: String): List<StoredHeader> =
            runCatching { json.decodeFromString(serializer, stored) }.getOrDefault(emptyList())
    }
}

/**
 * A named API key. Only a SHA-256 hash of the key is stored: the key itself is shown once, when
 * it's created or regenerated, and can't be recovered afterwards.
 */
@Entity(
    tableName = "api_keys",
    indices = [Index(value = ["name"], unique = true), Index(value = ["keyHash"], unique = true)],
)
data class ApiKeyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val keyHash: String,
    /** The first characters of the key, so you can tell keys apart, e.g. `sgw_1a2b3c4d`. */
    val keyPrefix: String,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
)
