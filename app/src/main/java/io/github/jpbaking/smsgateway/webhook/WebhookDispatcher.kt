package io.github.jpbaking.smsgateway.webhook

import android.content.Context
import io.github.jpbaking.smsgateway.data.ApiKeys
import io.github.jpbaking.smsgateway.data.MessageEntity
import io.github.jpbaking.smsgateway.data.WebhookDao
import io.github.jpbaking.smsgateway.data.WebhookEntity
import io.github.jpbaking.smsgateway.server.EventStream
import io.github.jpbaking.smsgateway.server.MessageDto
import io.github.jpbaking.smsgateway.server.WebhookPayload
import io.github.jpbaking.smsgateway.server.apiJson
import java.util.UUID

/**
 * Queues one delivery job per matching webhook. Jobs survive restarts; see [WebhookWorker] for
 * retries. The same payload also goes to the [EventStream], when that's on.
 *
 * A webhook that is switched off, or whose API key is disabled, gets nothing: events that happen
 * meanwhile are not kept for it and are not sent later.
 */
class WebhookDispatcher(
    private val context: Context,
    private val webhooks: WebhookDao,
    private val stream: EventStream,
    private val apiKeys: ApiKeys,
) {

    /** Whether [hook] may be delivered to right now: it is on, and so is the key that registered it. */
    suspend fun isActive(hook: WebhookEntity): Boolean =
        hook.enabled && (hook.createdByKeyId == null || apiKeys.allowsDelivery(hook.createdByKeyId))

    suspend fun dispatch(event: WebhookEvent, message: MessageEntity) {
        val targets = webhooks.all().filter { hook -> event.wireName in hook.eventNames() && isActive(hook) }
        if (targets.isEmpty() && !stream.enabled.value) return

        val payload = apiJson.encodeToString(
            WebhookPayload.serializer(),
            WebhookPayload(
                id = UUID.randomUUID().toString(),
                event = event.wireName,
                timestamp = System.currentTimeMillis(),
                message = MessageDto.from(message),
            ),
        )
        runCatching { stream.record(event.wireName, payload) }
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
