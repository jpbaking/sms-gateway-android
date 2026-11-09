package io.github.jpbaking.smsgateway.webhook

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** POSTs one webhook payload. Retries with backoff on network errors, 5xx, 408 and 429. */
class WebhookWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val secret = inputData.getString(KEY_SECRET) ?: ""
        val event = inputData.getString(KEY_EVENT) ?: ""
        val body = inputData.getString(KEY_BODY) ?: return@withContext Result.failure()
        val timestamp = System.currentTimeMillis() / 1000

        val code = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("User-Agent", "sms-gateway-android")
                conn.setRequestProperty("X-Gateway-Event", event)
                conn.setRequestProperty("X-Gateway-Timestamp", timestamp.toString())
                conn.setRequestProperty("X-Gateway-Signature", WebhookSigner.sign(secret, timestamp, body))
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Webhook $url failed (attempt ${runAttemptCount + 1})", e)
            -1
        }

        when {
            code in 200..299 -> Result.success()
            code in 400..499 && code != 408 && code != 429 -> {
                Log.w(TAG, "Webhook $url rejected with $code, giving up")
                Result.failure()
            }
            runAttemptCount + 1 >= MAX_ATTEMPTS -> Result.failure()
            else -> Result.retry()
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_SECRET = "secret"
        const val KEY_EVENT = "event"
        const val KEY_BODY = "body"
        private const val MAX_ATTEMPTS = 10
        private const val TAG = "WebhookWorker"
    }
}
