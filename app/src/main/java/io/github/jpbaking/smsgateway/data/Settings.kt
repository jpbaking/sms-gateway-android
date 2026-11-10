package io.github.jpbaking.smsgateway.data

import android.content.Context
import java.security.SecureRandom

/** Small key/value settings. SharedPreferences is enough here and works on every API level. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /** The single API key used by builds before named keys, if it hasn't been migrated yet. */
    fun legacyApiKey(): String? = prefs.getString(KEY_API_KEY, null)?.let { SecretBox.decrypt(it) }

    fun clearLegacyApiKey() = prefs.edit().remove(KEY_API_KEY).apply()

    /** Whether the server should come back after a reboot or app update. */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /** Longest wait between webhook retries. Early retries are quicker (10s, 20s, 40s, ...). */
    var webhookRetryIntervalSeconds: Int
        get() = prefs.getInt(KEY_RETRY_INTERVAL, DEFAULT_RETRY_INTERVAL_SECONDS)
        set(value) = prefs.edit().putInt(KEY_RETRY_INTERVAL, value).apply()

    /** How long to keep retrying a failed webhook, in hours. 0 means forever. */
    var webhookRetryMaxHours: Int
        get() = prefs.getInt(KEY_RETRY_MAX_HOURS, 0)
        set(value) = prefs.edit().putInt(KEY_RETRY_MAX_HOURS, value).apply()

    companion object {
        const val DEFAULT_PORT = 8080
        private const val KEY_PORT = "port"
        private const val KEY_API_KEY = "api_key"
        const val DEFAULT_RETRY_INTERVAL_SECONDS = 300
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_RETRY_INTERVAL = "webhook_retry_interval_s"
        private const val KEY_RETRY_MAX_HOURS = "webhook_retry_max_hours"

        fun randomToken(bytes: Int = 24): String {
            val buf = ByteArray(bytes)
            SecureRandom().nextBytes(buf)
            return buf.joinToString("") { "%02x".format(it) }
        }
    }
}
