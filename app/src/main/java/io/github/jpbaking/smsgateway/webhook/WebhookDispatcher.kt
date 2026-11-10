package io.github.jpbaking.smsgateway.webhook

import android.content.Context
import io.github.jpbaking.smsgateway.data.MessageEntity
import io.github.jpbaking.smsgateway.data.WebhookDao
import io.github.jpbaking.smsgateway.server.MessageDto
import io.github.jpbaking.smsgateway.server.WebhookPayload
import io.github.jpbaking.smsgateway.server.apiJson
import java.util.UUID

/** Queues one delivery job per matching webhook. Jobs survive restarts; see [WebhookWorker] for retries. */
class WebhookDispatcher(private val context: Context, private val webhooks: WebhookDao) {

    suspend fun dispatch(event: WebhookEvent, message: MessageEntity) {
        val targets = webhooks.all().filter { hook ->
            hook.events.split(',').any { it.trim() == event.wireName }
        }
        if (targets.isEmpty()) return

        val payload = apiJson.encodeToString(
            WebhookPayload.serializer(),
            WebhookPayload(
                id = UUID.randomUUID().toString(),
                event = event.wireName,
                timestamp = System.currentTimeMillis(),
                message = MessageDto.from(message),
            ),
        )
        val now = System.currentTimeMillis()
        for (hook in targets) {
            WebhookWorker.enqueue(
                context = context,
                webhookId = hook.id,
                event = event.wireName,
                body = payload,
                attempt = 0,
                firstAttemptAt = now,
                delayMillis = 0,
            )
        }
    }
}
