package io.github.jpbaking.smsgateway.sms

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.MessageStatus
import io.github.jpbaking.smsgateway.webhook.WebhookEvent
import kotlinx.coroutines.launch

/** Receives the per-part "sent" and "delivered" callbacks for outgoing messages. */
class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: return
        val resultCode = resultCode
        val app = context.applicationContext as GatewayApp
        val pending = goAsync()
        app.scope.launch {
            try {
                when (intent.action) {
                    ACTION_SENT -> onSent(app, id, resultCode)
                    ACTION_DELIVERED -> onDelivered(app, id)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun onSent(app: GatewayApp, id: String, resultCode: Int) {
        val dao = app.db.messages()
        val now = System.currentTimeMillis()
        if (resultCode != Activity.RESULT_OK) {
            val current = dao.get(id) ?: return
            if (current.status == MessageStatus.FAILED) return // another part already failed
            dao.setStatus(id, MessageStatus.FAILED, "Send failed (${sendErrorName(resultCode)})", now)
            dao.get(id)?.let { app.webhooks.dispatch(WebhookEvent.FAILED, it) }
            return
        }
        val updated = dao.recordPartSent(id, now) ?: return
        if (updated.status == MessageStatus.SENDING && updated.partsSent >= updated.partCount) {
            dao.setStatus(id, MessageStatus.SENT, null, now)
            dao.get(id)?.let { app.webhooks.dispatch(WebhookEvent.SENT, it) }
        }
    }

    private suspend fun onDelivered(app: GatewayApp, id: String) {
        val dao = app.db.messages()
        val now = System.currentTimeMillis()
        val updated = dao.recordPartDelivered(id, now) ?: return
        val stillOpen = updated.status == MessageStatus.SENDING || updated.status == MessageStatus.SENT
        if (stillOpen && updated.partsDelivered >= updated.partCount) {
            dao.setStatus(id, MessageStatus.DELIVERED, null, now)
            dao.get(id)?.let { app.webhooks.dispatch(WebhookEvent.DELIVERED, it) }
        }
    }

    private fun sendErrorName(code: Int): String = when (code) {
        1 -> "generic failure" // SmsManager.RESULT_ERROR_GENERIC_FAILURE
        2 -> "radio off" // RESULT_ERROR_RADIO_OFF
        3 -> "null PDU" // RESULT_ERROR_NULL_PDU
        4 -> "no service" // RESULT_ERROR_NO_SERVICE
        5 -> "rate limited" // RESULT_ERROR_LIMIT_EXCEEDED
        else -> "code $code"
    }

    companion object {
        const val ACTION_SENT = "io.github.jpbaking.smsgateway.SMS_SENT"
        const val ACTION_DELIVERED = "io.github.jpbaking.smsgateway.SMS_DELIVERED"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_PART = "part"
    }
}
