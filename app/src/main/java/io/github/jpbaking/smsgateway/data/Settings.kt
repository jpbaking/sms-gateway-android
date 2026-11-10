package io.github.jpbaking.smsgateway.data

import android.content.Context
import io.github.jpbaking.smsgateway.BuildConfig
import io.github.jpbaking.smsgateway.net.ListenMode
import io.github.jpbaking.smsgateway.tls.TlsMaterial
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

    /**
     * How long to keep retrying a failed webhook, in hours. Always between 1 and
     * [MAX_RETRY_MAX_HOURS]: retries never go on forever, see [retryMaxHours].
     */
    var webhookRetryMaxHours: Int
        get() = retryMaxHours(prefs.getInt(KEY_RETRY_MAX_HOURS, 0))
        set(value) = prefs.edit().putInt(KEY_RETRY_MAX_HOURS, retryMaxHours(value)).apply()

    /** Messages older than this many days are deleted by the daily cleanup. 0 keeps them forever. */
    var messageRetentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
        set(value) = prefs.edit().putInt(KEY_RETENTION_DAYS, value).apply()

    /** Whether the Cloudflare Tunnel runs alongside the server. */
    var tunnelEnabled: Boolean
        get() = prefs.getBoolean(KEY_TUNNEL_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TUNNEL_ENABLED, value).apply()

    var tunnelMode: TunnelMode
        get() = TunnelMode.entries.firstOrNull { it.name == prefs.getString(KEY_TUNNEL_MODE, null) } ?: TunnelMode.TOKEN
        set(value) = prefs.edit().putString(KEY_TUNNEL_MODE, value.name).apply()

    /**
     * Whether the tunnel is switched on and has what it needs to connect. [ListenMode.TUNNEL_ONLY]
     * is only offered while this is true, because without a tunnel nothing could reach the server.
     */
    val tunnelReady: Boolean
        get() = tunnelReady(tunnelEnabled, tunnelMode, hasToken = !tunnelToken.isNullOrBlank())

    /** Token of a tunnel created in the Cloudflare dashboard. Stored encrypted. */
    var tunnelToken: String?
        get() = prefs.getString(KEY_TUNNEL_TOKEN, null)?.let { SecretBox.decrypt(it) }
        set(value) {
            val editor = prefs.edit()
            if (value.isNullOrBlank()) {
                editor.remove(KEY_TUNNEL_TOKEN)
            } else {
                editor.putString(KEY_TUNNEL_TOKEN, SecretBox.encrypt(value.trim()))
            }
            editor.apply()
        }

    /** The public hostname set up for the tunnel in the dashboard. Only used for display. */
    var tunnelHostname: String
        get() = prefs.getString(KEY_TUNNEL_HOSTNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TUNNEL_HOSTNAME, value.trim()).apply()

    /** Whether `GET /api/v1/events` (Server-Sent Events) is served. Off by default: 404 when off. */
    var eventStreamEnabled: Boolean
        get() = prefs.getBoolean(KEY_EVENT_STREAM, false)
        set(value) = prefs.edit().putBoolean(KEY_EVENT_STREAM, value).apply()

    /** Whether the server also listens for HTTPS on [httpsPort]. */
    var httpsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HTTPS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HTTPS_ENABLED, value).apply()

    var httpsPort: Int
        get() = prefs.getInt(KEY_HTTPS_PORT, DEFAULT_HTTPS_PORT)
        set(value) = prefs.edit().putInt(KEY_HTTPS_PORT, value).apply()

    /**
     * With HTTPS on, whether plain HTTP is still reachable from other devices. When false, plain
     * HTTP only listens on 127.0.0.1, which the Cloudflare Tunnel still uses.
     */
    var httpOnLan: Boolean
        get() = prefs.getBoolean(KEY_HTTP_ON_LAN, false)
        set(value) = prefs.edit().putBoolean(KEY_HTTP_ON_LAN, value).apply()

    /** Who may connect to the server. New installs and upgrades both start on [ListenMode.LAN]. */
    var listenMode: ListenMode
        get() = ListenMode.entries.firstOrNull { it.name == prefs.getString(KEY_LISTEN_MODE, null) } ?: ListenMode.LAN
        set(value) = prefs.edit().putString(KEY_LISTEN_MODE, value.name).apply()

    /**
     * In [ListenMode.LAN], also let in 100.64.0.0/10, which Tailscale uses. Off by default,
     * because carriers use the same range behind their NAT.
     */
    var allowTailscale: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_TAILSCALE, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_TAILSCALE, value).apply()

    /** When true, the API can't add or remove webhooks: that's done in the app only. */
    var webhooksPhoneOnly: Boolean
        get() = prefs.getBoolean(KEY_WEBHOOKS_PHONE_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WEBHOOKS_PHONE_ONLY, value).apply()

    /** Set when an update turned off a quick tunnel that was in use, until the user dismisses the notice. */
    var quickTunnelRemovedNotice: Boolean
        get() = prefs.getBoolean(KEY_QUICK_TUNNEL_NOTICE, false)
        set(value) = prefs.edit().putBoolean(KEY_QUICK_TUNNEL_NOTICE, value).apply()

    /** Where the HTTPS certificate came from, or null if none is set up. */
    val tlsSource: TlsSource?
        get() = TlsSource.entries.firstOrNull { it.name == prefs.getString(KEY_TLS_SOURCE, null) }

    /**
     * The HTTPS certificate and key, or null if none is saved (or it can't be read any more).
     * The certificate is public and stored as PEM; the private key is stored encrypted.
     */
    fun tlsMaterial(): TlsMaterial? {
        val cert = prefs.getString(KEY_TLS_CERT, null) ?: return null
        val key = prefs.getString(KEY_TLS_KEY, null) ?: return null
        return runCatching { TlsMaterial.fromPem(cert + "\n" + SecretBox.decrypt(key)) }.getOrNull()
    }

    fun saveTls(material: TlsMaterial, source: TlsSource) {
        prefs.edit()
            .putString(KEY_TLS_CERT, material.certificatePem())
            .putString(KEY_TLS_KEY, SecretBox.encrypt(material.privateKeyPem()))
            .putString(KEY_TLS_SOURCE, source.name)
            .apply()
    }

    fun clearTls() {
        prefs.edit().remove(KEY_TLS_CERT).remove(KEY_TLS_KEY).remove(KEY_TLS_SOURCE).apply()
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_HTTPS_PORT = 8443
        private const val KEY_LISTEN_MODE = "listen_mode"
        private const val KEY_ALLOW_TAILSCALE = "allow_tailscale"
        private const val KEY_WEBHOOKS_PHONE_ONLY = "webhooks_phone_only"
        private const val KEY_QUICK_TUNNEL_NOTICE = "quick_tunnel_removed_notice"
        private const val KEY_EVENT_STREAM = "event_stream_enabled"
        private const val KEY_HTTPS_ENABLED = "https_enabled"
        private const val KEY_HTTPS_PORT = "https_port"
        private const val KEY_HTTP_ON_LAN = "http_on_lan"
        private const val KEY_TLS_SOURCE = "tls_source"
        private const val KEY_TLS_CERT = "tls_cert_pem"
        private const val KEY_TLS_KEY = "tls_key"
        private const val KEY_TUNNEL_ENABLED = "tunnel_enabled"
        private const val KEY_TUNNEL_MODE = "tunnel_mode"
        private const val KEY_TUNNEL_TOKEN = "tunnel_token"
        private const val KEY_TUNNEL_HOSTNAME = "tunnel_hostname"
        private const val KEY_PORT = "port"
        private const val KEY_API_KEY = "api_key"
        const val DEFAULT_RETRY_INTERVAL_SECONDS = 300

        /** Three days: long enough for a server to come back after a weekend outage. */
        const val DEFAULT_RETRY_MAX_HOURS = 72

        /** Thirty days. */
        const val MAX_RETRY_MAX_HOURS = 720

        /**
         * The hours to retry for, from a stored or typed value. Zero used to mean "retry forever"
         * and was the default; it now means the default, so a delivery never stays queued without
         * end for a URL that may never answer, or that may belong to someone else by the time it
         * does.
         */
        fun retryMaxHours(stored: Int): Int =
            if (stored <= 0) DEFAULT_RETRY_MAX_HOURS else stored.coerceAtMost(MAX_RETRY_MAX_HOURS)

        /** See [Settings.tunnelReady]. A quick tunnel needs no token, but only counts where it is offered. */
        fun tunnelReady(
            enabled: Boolean,
            mode: TunnelMode,
            hasToken: Boolean,
            quickTunnels: Boolean = BuildConfig.QUICK_TUNNEL_ENABLED,
        ): Boolean = enabled && when (mode) {
            TunnelMode.TOKEN -> hasToken
            TunnelMode.QUICK -> quickTunnels
        }
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_RETRY_INTERVAL = "webhook_retry_interval_s"
        private const val KEY_RETRY_MAX_HOURS = "webhook_retry_max_hours"
        private const val KEY_RETENTION_DAYS = "message_retention_days"
        const val DEFAULT_RETENTION_DAYS = 30

        fun randomToken(bytes: Int = 24): String {
            val buf = ByteArray(bytes)
            SecureRandom().nextBytes(buf)
            return buf.joinToString("") { "%02x".format(it) }
        }
    }
}

enum class TunnelMode {
    /** A named tunnel from the user's Cloudflare account, run with its token. Stable hostname. */
    TOKEN,

    /**
     * A throwaway trycloudflare.com tunnel. No account needed; the URL changes on every start.
     * Only offered when `BuildConfig.QUICK_TUNNEL_ENABLED` is true, which it isn't: it makes it
     * too easy to put the gateway on the internet without meaning to.
     */
    QUICK,
}

enum class TlsSource(val label: String) {
    PEM("Pasted certificate and key"),
    PKCS12("Imported PKCS#12 file"),
    SELF_SIGNED("Self-signed, made on this phone"),
}
