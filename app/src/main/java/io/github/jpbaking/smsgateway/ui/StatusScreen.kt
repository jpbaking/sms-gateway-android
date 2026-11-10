package io.github.jpbaking.smsgateway.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.TunnelMode
import io.github.jpbaking.smsgateway.net.ListenMode
import io.github.jpbaking.smsgateway.service.GatewayService
import io.github.jpbaking.smsgateway.service.ServerState
import io.github.jpbaking.smsgateway.service.localIpv4Addresses
import io.github.jpbaking.smsgateway.tunnel.TunnelState

private val requiredPermissions: Array<String> = buildList {
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.RECEIVE_SMS)
    add(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/** Home: whether the server runs, where to reach it, the SIMs, and anything that needs attention. No settings here. */
@Composable
internal fun StatusScreen(app: GatewayApp, padding: PaddingValues, openSettings: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    // Bumped when coming back to the app, since permissions and battery settings change outside it.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val missing = remember(refresh) {
        requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh++ }

    val state by GatewayService.state.collectAsStateWithLifecycle()
    val keys by remember { app.apiKeys.observe() }.collectAsStateWithLifecycle(initialValue = null)
    val sims = remember(refresh) { app.sims.activeSims() }

    ScreenColumn(padding) {
        if (missing.isNotEmpty()) {
            Section("Permissions needed") {
                Text("The gateway needs SMS and phone permissions to send, receive and pick a SIM.")
                Button(onClick = { permissionLauncher.launch(requiredPermissions) }) { Text("Grant permissions") }
            }
        }

        ServerCard(app, state)

        if (state is ServerState.Running) AddressesCard(app, state as ServerState.Running)

        Attention(app, keys?.isEmpty() == true, keys.orEmpty().count { it.needsReview }, refresh, openSettings)

        Section("SIM cards") {
            if (sims.isEmpty()) Text("No active SIMs found (or phone permission not granted).")
            sims.forEach { sim ->
                Column {
                    Text(
                        "SIM ${sim.slot + 1}: ${sim.carrierName ?: sim.displayName ?: "Unknown"}" +
                            (if (sim.isDefaultSms) " (default)" else ""),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        listOfNotNull(sim.number, "simSlot=${sim.slot}", "subscriptionId=${sim.subscriptionId}")
                            .joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerCard(app: GatewayApp, state: ServerState) {
    val context = LocalContext.current
    var confirmStop by remember { mutableStateOf(false) }
    val running = state is ServerState.Running
    Section("Server") {
        Text(
            when (state) {
                is ServerState.Running -> "● Running"
                is ServerState.Error -> "● Not running"
                ServerState.Stopped -> "● Stopped"
            },
            style = MaterialTheme.typography.headlineSmall,
            color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state is ServerState.Error) {
            Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (running) {
            OutlinedButton(onClick = { confirmStop = true }, modifier = Modifier.fillMaxWidth()) { Text("Stop server") }
        } else {
            Button(onClick = { GatewayService.start(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Start server")
            }
            Text(
                "HTTP port ${app.settings.port}" +
                    (if (app.settings.httpsEnabled) ", HTTPS port ${app.settings.httpsPort}" else "") +
                    ". Change these in Settings.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (confirmStop) {
        ConfirmDialog(
            title = "Stop the server?",
            text = "The API stops answering and incoming SMS are no longer forwarded to webhooks until you start it again.",
            confirmLabel = "Stop",
            onDismiss = { confirmStop = false },
            onConfirm = {
                confirmStop = false
                GatewayService.stop(context)
            },
        )
    }
}

@Composable
private fun AddressesCard(app: GatewayApp, s: ServerState.Running) {
    val tunnelState by app.tunnel.state.collectAsStateWithLifecycle()
    val quickUrl by app.tunnel.quickUrl.collectAsStateWithLifecycle()
    Section("Reach the API at") {
        SelectionContainer {
            Column {
                // In "Tunnel only" nothing on the network can connect, so there are no addresses to show.
                if (app.settings.listenMode != ListenMode.TUNNEL_ONLY) {
                    localIpv4Addresses().forEach { ip ->
                        s.httpsPort?.let { Text("https://$ip:$it", fontFamily = FontFamily.Monospace) }
                        if (!s.httpLocalOnly) Text("http://$ip:${s.port}", fontFamily = FontFamily.Monospace)
                    }
                }
                if (tunnelState is TunnelState.Connected) {
                    val url = when (app.settings.tunnelMode) {
                        TunnelMode.QUICK -> quickUrl
                        TunnelMode.TOKEN -> app.settings.tunnelHostname.takeIf { it.isNotBlank() }
                            ?.let { "https://${it.removePrefix("https://")}" }
                    }
                    url?.let { Text("$it  (internet)", fontFamily = FontFamily.Monospace) }
                }
            }
        }
        if (app.settings.tunnelEnabled && tunnelState !is TunnelState.Connected && tunnelState != TunnelState.Off) {
            Text("Tunnel: ${tunnelStatusText(tunnelState)}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            when (app.settings.listenMode) {
                ListenMode.TUNNEL_ONLY -> if (app.settings.tunnelEnabled) {
                    "Only the Cloudflare Tunnel can connect."
                } else {
                    "Only the Cloudflare Tunnel can connect, and the tunnel is off, so nothing can reach the API."
                }
                ListenMode.LAN -> "Only devices on the local network can connect. Mobile data and public addresses are refused."
                ListenMode.ANYWHERE -> "Anything that can reach this phone can connect."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Things that stop the gateway from working well, each with a way to fix it. */
@SuppressLint("BatteryLife")
@Composable
private fun Attention(
    app: GatewayApp,
    noKeys: Boolean,
    keysToReview: Int,
    refresh: Int,
    openSettings: (SettingsPage) -> Unit,
) {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    val batteryRestricted = remember(refresh) { !power.isIgnoringBatteryOptimizations(context.packageName) }
    val httpsWithoutCert = remember(refresh) { app.settings.httpsEnabled && app.settings.tlsMaterial() == null }
    var quickTunnelNotice by remember { mutableStateOf(app.settings.quickTunnelRemovedNotice) }
    // "Tunnel only" can't be picked without a working tunnel, but an install from before that rule
    // can still be in this state.
    val tunnelOnlyWithoutTunnel = remember(refresh) {
        app.settings.listenMode == ListenMode.TUNNEL_ONLY && !(app.tunnel.isBundled && app.settings.tunnelReady)
    }
    if (!noKeys && !batteryRestricted && !httpsWithoutCert && keysToReview == 0 && !quickTunnelNotice &&
        !tunnelOnlyWithoutTunnel
    ) {
        return
    }

    Section("Needs attention") {
        if (quickTunnelNotice) {
            Warning(
                "Quick tunnels have been removed, because they made it too easy to put the gateway on the " +
                    "internet by accident. Your tunnel was turned off. To reach the gateway from outside, set " +
                    "up a tunnel from your own Cloudflare account.",
            )
            TextButton(onClick = { openSettings(SettingsPage.TUNNEL) }) { Text("Set up a tunnel") }
            TextButton(onClick = {
                app.settings.quickTunnelRemovedNotice = false
                quickTunnelNotice = false
            }) { Text("Dismiss") }
        }
        if (tunnelOnlyWithoutTunnel) {
            Warning(
                "\"Who can connect\" is set to \"${ListenMode.TUNNEL_ONLY.label}\", but the Cloudflare Tunnel is off " +
                    "or has no token, so nothing can reach the API.",
            )
            TextButton(onClick = { openSettings(SettingsPage.TUNNEL) }) { Text("Set up the tunnel") }
            TextButton(onClick = { openSettings(SettingsPage.SERVER) }) { Text("Change who can connect") }
        }
        if (keysToReview > 0) {
            Warning(
                (if (keysToReview == 1) "1 API key has" else "$keysToReview API keys have") +
                    " every permission, from before permissions existed. Keep only what each one needs.",
            )
            TextButton(onClick = { openSettings(SettingsPage.API_KEYS) }) { Text("Review API keys") }
        }
        if (noKeys) {
            Warning("No API keys yet, so the API refuses every request.")
            TextButton(onClick = { openSettings(SettingsPage.API_KEYS) }) { Text("Create an API key") }
        }
        if (httpsWithoutCert) {
            Warning("HTTPS is on but there's no certificate, so the server can't start.")
            TextButton(onClick = { openSettings(SettingsPage.SERVER) }) { Text("Set up HTTPS") }
        }
        if (batteryRestricted) {
            Warning("Battery optimization may pause the server when the screen is off.")
            TextButton(onClick = {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${context.packageName}")),
                )
            }) { Text("Allow running in background") }
        }
    }
}

@Composable
private fun Warning(text: String, color: Color = MaterialTheme.colorScheme.error) {
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
}
