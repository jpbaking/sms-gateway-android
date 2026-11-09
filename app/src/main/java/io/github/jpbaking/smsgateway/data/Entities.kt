package io.github.jpbaking.smsgateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
)

@Entity(tableName = "webhooks")
data class WebhookEntity(
    @PrimaryKey val id: String,
    val url: String,
    /** Comma separated event names, see [io.github.jpbaking.smsgateway.webhook.WebhookEvent]. */
    val events: String,
    /** Shared secret used to HMAC-sign each payload. */
    val secret: String,
    val createdAt: Long,
)
