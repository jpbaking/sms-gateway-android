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
import io.github.jpbaking.smsgateway.server.GatewayServer
import io.github.jpbaking.smsgateway.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ServerState {
    data object Stopped : ServerState
    data class Running(val port: Int) : ServerState
    data class Error(val message: String) : ServerState
}

/**
 * Keeps the HTTP server alive. Runs as a foreground service with a persistent notification,
 * and holds wake and Wi-Fi locks so the phone stays reachable with the screen off.
 */
class GatewayService : Service() {

    private lateinit var server: GatewayServer
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
        val port = app.settings.port
        goForeground(port)
        if (!server.isRunning) {
            try {
                server.start(port)
                acquireLocks()
                _state.value = ServerState.Running(port)
                resendQueued(app)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start server on port $port", e)
                _state.value = ServerState.Error("Could not start on port $port: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
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

    private fun goForeground(port: Int) {
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
            .setContentTitle("SMS gateway running")
            .setContentText("http://$address:$port")
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

        private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
        val state: StateFlow<ServerState> = _state.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GatewayService::class.java))
        }
    }
}
