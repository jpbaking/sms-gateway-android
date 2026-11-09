package io.github.jpbaking.smsgateway.webhook

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.jpbaking.smsgateway.data.MessageEntity
import io.github.jpbaking.smsgateway.data.WebhookDao
import io.github.jpbaking.smsgateway.server.MessageDto
import io.github.jpbaking.smsgateway.server.WebhookPayload
import io.github.jpbaking.smsgateway.server.apiJson
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Queues one WorkManager job per matching webhook, so deliveries survive restarts and retry. */
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
        val workManager = WorkManager.getInstance(context)
        for (hook in targets) {
            val request = OneTimeWorkRequestBuilder<WebhookWorker>()
                .setInputData(
                    workDataOf(
                        WebhookWorker.KEY_URL to hook.url,
                        WebhookWorker.KEY_SECRET to hook.secret,
                        WebhookWorker.KEY_EVENT to event.wireName,
                        WebhookWorker.KEY_BODY to payload,
                    ),
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            workManager.enqueue(request)
        }
    }

    companion object {
        const val TAG = "webhook"
    }
}
