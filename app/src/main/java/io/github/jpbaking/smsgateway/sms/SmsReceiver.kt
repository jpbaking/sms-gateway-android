package io.github.jpbaking.smsgateway.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.Direction
import io.github.jpbaking.smsgateway.data.MessageEntity
import io.github.jpbaking.smsgateway.data.MessageStatus
import io.github.jpbaking.smsgateway.webhook.WebhookEvent
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Stores every incoming SMS and fires the `sms:received` webhook. Registered in the manifest,
 * so it works even when the HTTP server is stopped.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pdus = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (pdus.isEmpty()) return

        // Which SIM it arrived on. The extra names changed over the years, so check both.
        val subId = intent.getIntExtra(EXTRA_SUBSCRIPTION_INDEX, intent.getIntExtra("subscription", -1))
        val slotExtra = intent.getIntExtra(EXTRA_SLOT_INDEX, intent.getIntExtra("slot", -1))

        // A long message arrives as several PDUs in one broadcast; join them per sender.
        val bySender = pdus.filterNotNull().groupBy { it.displayOriginatingAddress ?: "" }

        val app = context.applicationContext as GatewayApp
        val pending = goAsync()
        app.scope.launch {
            try {
                val slot = if (slotExtra >= 0) slotExtra else app.sims.bySubscriptionId(subId)?.slot ?: -1
                val now = System.currentTimeMillis()
                for ((sender, parts) in bySender) {
                    val message = MessageEntity(
                        id = UUID.randomUUID().toString(),
                        direction = Direction.IN,
                        phoneNumber = sender,
                        body = parts.joinToString("") { it.displayMessageBody ?: "" },
                        subscriptionId = subId,
                        simSlot = slot,
                        status = MessageStatus.RECEIVED,
                        partCount = parts.size,
                        createdAt = parts.first().timestampMillis.takeIf { it > 0 } ?: now,
                        updatedAt = now,
                    )
                    app.db.messages().insert(message)
                    app.webhooks.dispatch(WebhookEvent.RECEIVED, message)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        // Values of SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX / EXTRA_SLOT_INDEX (API 30+),
        // which older Android versions also send.
        const val EXTRA_SUBSCRIPTION_INDEX = "android.telephony.extra.SUBSCRIPTION_INDEX"
        const val EXTRA_SLOT_INDEX = "android.telephony.extra.SLOT_INDEX"
    }
}
