package io.github.jpbaking.smsgateway.webhook

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.SecretBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.TimeUnit

/**
 * POSTs one webhook payload.
 *
 * On network errors, 5xx, 408 and 429 it schedules the next attempt itself, waiting
 * 10s, 20s, 40s, ... up to the configured retry interval (default 5 minutes), and keeps going
 * forever unless a maximum retry time is set. (WorkManager's own backoff can't be capped below
 * 5 hours, which would leave a recovered server waiting far too long.) While the phone is
 * offline, the network constraint holds the job back without spending attempts.
 */
class WebhookWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val webhookId = inputData.getString(KEY_WEBHOOK_ID) ?: return@withContext Result.failure()
        val app = applicationContext as GatewayApp
        // Read the webhook fresh on every attempt: deleting it stops retries, and secrets stay
        // encrypted in the database instead of being copied into WorkManager's own storage.
        val hook = app.db.webhooks().get(webhookId) ?: return@withContext Result.success()
        val url = hook.url
        val secret = SecretBox.decrypt(hook.secret)
        val extraHeaders = hook.storedHeaders().map {
            it.key to if (it.isSecret) SecretBox.decrypt(it.value) else it.value
        }
        val event = inputData.getString(KEY_EVENT) ?: ""
        val body = inputData.getString(KEY_BODY) ?: return@withContext Result.failure()
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        val firstAttemptAt = inputData.getLong(KEY_FIRST_ATTEMPT_AT, System.currentTimeMillis())

        val code = post(url, hook.insecure, secret, extraHeaders, event, body)
        if (code in 200..299) return@withContext Result.success()
        if (code in 400..499 && code != 408 && code != 429) {
            Log.w(TAG, "Webhook $url rejected with $code, giving up")
            return@withContext Result.failure()
        }

        val delay = nextDelayMillis(attempt, app.settings.webhookRetryIntervalSeconds * 1000L)
        val maxAgeMs = app.settings.webhookRetryMaxHours * 3_600_000L
        if (maxAgeMs > 0 && System.currentTimeMillis() + delay - firstAttemptAt > maxAgeMs) {
            Log.w(TAG, "Webhook $url still failing after ${attempt + 1} attempts, giving up")
            return@withContext Result.failure()
        }
        enqueue(applicationContext, webhookId, event, body, attempt + 1, firstAttemptAt, delay)
        Result.success()
    }

    /** Returns the HTTP status code, or -1 if the request didn't complete. */
    private fun post(url: String, insecure: Boolean, secret: String, extraHeaders: List<Pair<String, String>>, event: String, body: String): Int {
        val timestamp = System.currentTimeMillis() / 1000
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            if (insecure && conn is HttpsURLConnection) InsecureTls.apply(conn)
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("User-Agent", "sms-gateway-android")
                // Extra headers go after the defaults (so they can replace User-Agent) and before
                // the X-Gateway-* headers (which the API refuses to let them override).
                for ((key, value) in extraHeaders) conn.setRequestProperty(key, value)
                conn.setRequestProperty("X-Gateway-Event", event)
                conn.setRequestProperty("X-Gateway-Timestamp", timestamp.toString())
                conn.setRequestProperty("X-Gateway-Signature", WebhookSigner.sign(secret, timestamp, body))
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Webhook $url failed: ${e.message}")
            -1
        }
    }

    companion object {
        private const val KEY_WEBHOOK_ID = "webhook_id"
        private const val KEY_EVENT = "event"
        private const val KEY_BODY = "body"
        private const val KEY_ATTEMPT = "attempt"
        private const val KEY_FIRST_ATTEMPT_AT = "first_attempt_at"
        private const val TAG = "WebhookWorker"

        const val WORK_TAG = "webhook"
        const val BASE_DELAY_MS = 10_000L

        /** 10s, 20s, 40s, ... doubling per attempt, never more than [maxDelayMs]. */
        fun nextDelayMillis(attempt: Int, maxDelayMs: Long): Long =
            (BASE_DELAY_MS shl attempt.coerceIn(0, 20)).coerceAtMost(maxDelayMs.coerceAtLeast(BASE_DELAY_MS))

        fun enqueue(
            context: Context,
            webhookId: String,
            event: String,
            body: String,
            attempt: Int,
            firstAttemptAt: Long,
            delayMillis: Long,
        ) {
            val request = OneTimeWorkRequestBuilder<WebhookWorker>()
                .setInputData(
                    workDataOf(
                        KEY_WEBHOOK_ID to webhookId,
                        KEY_EVENT to event,
                        KEY_BODY to body,
                        KEY_ATTEMPT to attempt,
                        KEY_FIRST_ATTEMPT_AT to firstAttemptAt,
                    ),
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
