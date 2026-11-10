package io.github.jpbaking.smsgateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.R
import io.github.jpbaking.smsgateway.net.AccessPolicy
import io.github.jpbaking.smsgateway.net.ListenMode
import io.github.jpbaking.smsgateway.server.GatewayServer
import io.github.jpbaking.smsgateway.tls.TlsFrontend
import io.github.jpbaking.smsgateway.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ServerState {
    data object Stopped : ServerState
    /**
     * [port] is the plain HTTP port; [httpsPort] is set when HTTPS is on. With [httpLocalOnly],
     * plain HTTP only answers on 127.0.0.1 (for the tunnel), so other devices must use HTTPS.
     */
    data class Running(val port: Int, val httpsPort: Int? = null, val httpLocalOnly: Boolean = false) : ServerState
    data class Error(val message: String) : ServerState
}

/**
 * Keeps the HTTP server alive. Runs as a foreground service with a persistent notification,
 * and holds wake and Wi-Fi locks so the phone stays reachable with the screen off.
 */
class GatewayService : Service() {

    private lateinit var server: GatewayServer
    private var tls: TlsFrontend? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        server = GatewayServer(application as GatewayApp)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val app = application as GatewayApp
        if (intent?.action == ACTION_RESTART && server.isRunning) {
            // Settings changed: shut the listeners down and bring them back up below with the new values.
            app.tunnel.setServerRunning(false)
            tls?.stop()
            tls = null
            server.stop()
            releaseLocks()
        }
        val settings = app.settings
        val port = settings.port
        val mode = settings.listenMode
        val tunnelOnly = mode == ListenMode.TUNNEL_ONLY
        // Tunnel only: nothing but this phone connects, so there is nobody to serve HTTPS to.
        val httpsPort = settings.httpsPort.takeIf { settings.httpsEnabled && !tunnelOnly }
        val httpLocalOnly = tunnelOnly || (httpsPort != null && !settings.httpOnLan)
        goForeground(port, httpsPort, httpLocalOnly)
        if (!server.isRunning) {
            try {
                // Read the certificate first, so a bad one doesn't leave plain HTTP running alone.
                val material = httpsPort?.let {
                    settings.tlsMaterial()
                        ?: throw IllegalStateException("HTTPS is on, but no certificate is set up")
                }
                app.cellular.start()
                val policy = AccessPolicy(mode, settings.allowTailscale, app.cellular::contains)
                server.start(port, host = if (httpLocalOnly) "127.0.0.1" else "0.0.0.0", policy = policy)
                if (material != null && httpsPort != null) {
                    tls = TlsFrontend(
                        sslContext = material.sslContext(),
                        port = httpsPort,
                        backendPort = port,
                        accept = policy::allows,
                        onError = { message, e -> Log.w(TAG, message, e) },
                    ).also { it.start() }
                }
                acquireLocks()
                _state.value = ServerState.Running(port, httpsPort, httpLocalOnly)
                resendQueued(app)
                app.tunnel.setServerRunning(true, port)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start server", e)
                val ports = listOfNotNull(port, httpsPort).joinToString(" and ")
                _state.value = ServerState.Error("Could not start on port $ports: ${e.message}")
                tls?.stop()
                tls = null
                server.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val app = application as GatewayApp
        app.tunnel.setServerRunning(false)
        app.cellular.stop()
        tls?.stop()
        tls = null
        server.stop()
        releaseLocks()
        if (_state.value is ServerState.Running) _state.value = ServerState.Stopped
        super.onDestroy()
    }

    /** Messages accepted by the API but not yet handed to the radio when the app last died. */
    private fun resendQueued(app: GatewayApp) {
        app.scope.launch {
            for (message in app.db.messages().queued()) {
                val sim = message.subscriptionId.takeIf { it >= 0 }?.let { app.sims.bySubscriptionId(it) }
                    ?: app.sims.resolve(null, null)
                app.sender.send(message, sim)
            }
        }
    }

    private fun goForeground(port: Int, httpsPort: Int?, httpLocalOnly: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GatewayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val address = localIpv4Addresses().firstOrNull() ?: "this phone"
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.brand_green))
            .setContentTitle("SMS gateway running")
            .setContentText(
                listOfNotNull(
                    httpsPort?.let { "https://$address:$it" },
                    "http://$address:$port".takeUnless { httpLocalOnly },
                ).joinToString("  ").ifEmpty { "Reachable through the tunnel only" },
            )
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsGateway::server").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SmsGateway::server").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val TAG = "GatewayService"
        private const val CHANNEL_ID = "gateway"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.github.jpbaking.smsgateway.STOP"
        private const val ACTION_RESTART = "io.github.jpbaking.smsgateway.RESTART"

        private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
        val state: StateFlow<ServerState> = _state.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
        }

        /** Restarts the server so it picks up changed ports or HTTPS settings. Starts it if it's stopped. */
        fun restart(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GatewayService::class.java).setAction(ACTION_RESTART),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayService::class.java))
        }
    }
}
