package io.github.jpbaking.smsgateway.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import io.github.jpbaking.smsgateway.data.MessageDao
import io.github.jpbaking.smsgateway.data.MessageEntity
import io.github.jpbaking.smsgateway.data.MessageStatus
import io.github.jpbaking.smsgateway.webhook.WebhookDispatcher
import io.github.jpbaking.smsgateway.webhook.WebhookEvent

/** Hands outgoing messages to the right SIM's SmsManager and wires up the status callbacks. */
class SmsSender(
    private val context: Context,
    private val messages: MessageDao,
    private val webhooks: WebhookDispatcher,
) {

    suspend fun send(message: MessageEntity, sim: SimInfo?) {
        val now = System.currentTimeMillis()
        try {
            val manager = smsManagerFor(sim?.subscriptionId)
            val parts = manager.divideMessage(message.body)
            messages.markSending(
                id = message.id,
                parts = parts.size,
                subId = sim?.subscriptionId ?: -1,
                slot = sim?.slot ?: -1,
                now = now,
            )
            val sent = ArrayList(parts.indices.map { statusIntent(SmsStatusReceiver.ACTION_SENT, message.id, it) })
            val delivered = ArrayList(parts.indices.map { statusIntent(SmsStatusReceiver.ACTION_DELIVERED, message.id, it) })
            manager.sendMultipartTextMessage(message.phoneNumber, null, parts, sent, delivered)
        } catch (e: Exception) {
            Log.e(TAG, "Sending ${message.id} failed", e)
            messages.setStatus(message.id, MessageStatus.FAILED, e.message ?: e.javaClass.simpleName, now)
            messages.get(message.id)?.let { webhooks.dispatch(WebhookEvent.FAILED, it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManagerFor(subscriptionId: Int?): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subscriptionId != null) base.createForSubscriptionId(subscriptionId) else base
        } else {
            if (subscriptionId != null) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }
        }
    }

    private fun statusIntent(action: String, messageId: String, part: Int): PendingIntent {
        // The data URI makes each intent distinct, so PendingIntents for different parts and
        // messages are never merged by the system.
        val intent = Intent(action, Uri.parse("smsgateway://status/$messageId/$part"))
            .setClass(context, SmsStatusReceiver::class.java)
            .putExtra(SmsStatusReceiver.EXTRA_MESSAGE_ID, messageId)
            .putExtra(SmsStatusReceiver.EXTRA_PART, part)
        // The telephony stack adds the delivery report PDU as an extra, so it must be mutable.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private companion object {
        const val TAG = "SmsSender"
    }
}
