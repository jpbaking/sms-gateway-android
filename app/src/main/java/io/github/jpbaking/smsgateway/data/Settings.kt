package io.github.jpbaking.smsgateway.data

import android.content.Context
import java.security.SecureRandom

/** Small key/value settings. SharedPreferences is enough here and works on every API level. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /** Generated on first use so a fresh install is never open without a key. */
    val apiKey: String
        get() = prefs.getString(KEY_API_KEY, null) ?: regenerateApiKey()

    fun regenerateApiKey(): String {
        val key = randomToken()
        prefs.edit().putString(KEY_API_KEY, key).apply()
        return key
    }

    /** Whether the server should come back after a reboot or app update. */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    companion object {
        const val DEFAULT_PORT = 8080
        private const val KEY_PORT = "port"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_AUTO_START = "auto_start"

        fun randomToken(bytes: Int = 24): String {
            val buf = ByteArray(bytes)
            SecureRandom().nextBytes(buf)
            return buf.joinToString("") { "%02x".format(it) }
        }
    }
}
