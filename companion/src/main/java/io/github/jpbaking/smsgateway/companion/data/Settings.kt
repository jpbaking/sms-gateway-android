package io.github.jpbaking.smsgateway.companion.data

import android.content.Context
import io.github.jpbaking.smsgateway.companion.api.GatewayConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the gateway is and how to talk to it. The API key is stored encrypted. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())

    /** Null until a URL and API key are saved. */
    val config: StateFlow<GatewayConfig?> = _config.asStateFlow()

    private fun read(): GatewayConfig? {
        val url = prefs.getString(KEY_URL, null) ?: return null
        val key = prefs.getString(KEY_API_KEY, null)
            ?.let { runCatching { SecretBox.decrypt(it) }.getOrNull() }
            ?: return null
        return GatewayConfig(url, key, prefs.getString(KEY_PIN, null))
    }

    fun save(config: GatewayConfig) {
        prefs.edit()
            .putString(KEY_URL, config.baseUrl)
            .putString(KEY_API_KEY, SecretBox.encrypt(config.apiKey))
            .putString(KEY_PIN, config.pinnedSha256)
            .apply()
        _config.value = config
    }

    private companion object {
        const val KEY_URL = "gateway_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_PIN = "pinned_cert_sha256"
    }
}
