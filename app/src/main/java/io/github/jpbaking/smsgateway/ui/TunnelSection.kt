package io.github.jpbaking.smsgateway.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.TunnelMode
import io.github.jpbaking.smsgateway.tunnel.TunnelState

/** Settings and status for the built-in Cloudflare Tunnel, which makes the API reachable from the internet. */
@Composable
fun TunnelSection(app: GatewayApp, port: Int) {
    val tunnel = app.tunnel
    if (!tunnel.isBundled) {
        Section("Cloudflare Tunnel") {
            Text(
                "This build doesn't include cloudflared for this phone's processor, so the tunnel isn't available.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        return
    }
    TunnelSettings(app, port)
    TunnelLogCard(app)
}

@Composable
private fun TunnelSettings(app: GatewayApp, port: Int) {
    val tunnel = app.tunnel
    val settings = app.settings
    val state by tunnel.state.collectAsStateWithLifecycle()
    val quickUrl by tunnel.quickUrl.collectAsStateWithLifecycle()

    var editing by rememberSaveable { mutableStateOf(false) }
    var enabled by rememberSaveable { mutableStateOf(settings.tunnelEnabled) }
    var mode by rememberSaveable { mutableStateOf(settings.tunnelMode) }
    var tokenText by remember { mutableStateOf("") }
    var hostname by rememberSaveable { mutableStateOf(settings.tunnelHostname) }
    // Bumped when the saved token changes, to re-read it.
    var tokenVersion by remember { mutableIntStateOf(0) }
    val hasToken = remember(tokenVersion) { !settings.tunnelToken.isNullOrBlank() }
    var pending by remember { mutableStateOf<PendingChange?>(null) }
    var confirmRemoveToken by remember { mutableStateOf(false) }

    fun reset() {
        enabled = settings.tunnelEnabled
        mode = settings.tunnelMode
        tokenText = ""
        hostname = settings.tunnelHostname
    }

    EditableSection(
        title = "Cloudflare Tunnel",
        editing = editing,
        onEditingChange = { editing = it },
        dirty = enabled != settings.tunnelEnabled ||
            mode != settings.tunnelMode ||
            tokenText.isNotBlank() ||
            hostname != settings.tunnelHostname,
        onDiscard = ::reset,
        onSave = save@{
            if (enabled && mode == TunnelMode.TOKEN && !hasToken && tokenText.isBlank()) {
                return@save "Paste the tunnel token from the Cloudflare dashboard."
            }
            val commit = {
                if (tokenText.isNotBlank()) {
                    settings.tunnelToken = tokenText
                    tokenVersion++
                }
                settings.tunnelHostname = hostname
                settings.tunnelMode = mode
                settings.tunnelEnabled = enabled
                tunnel.reload()
                editing = false
                reset()
            }
            if (enabled && !settings.tunnelEnabled) {
                pending = PendingChange(
                    title = "Make the API public?",
                    text = "Anyone on the internet who has the tunnel's URL can reach the API. Requests still " +
                        "need an API key, so keep your keys secret.",
                    confirmLabel = "Turn on",
                    commit = commit,
                )
            } else {
                commit()
            }
            null
        },
        view = {
            Text(
                "Makes the API reachable from the internet without opening ports.",
                style = MaterialTheme.typography.bodySmall,
            )
            ValueRow("Tunnel", onOff(settings.tunnelEnabled))
            ValueRow(
                "Kind",
                when (settings.tunnelMode) {
                    TunnelMode.TOKEN -> "My Cloudflare tunnel"
                    TunnelMode.QUICK -> "Quick tunnel"
                },
            )
            if (settings.tunnelMode == TunnelMode.TOKEN) {
                ValueRow("Token", if (hasToken) "Saved" else "Not set")
                ValueRow("Public hostname", settings.tunnelHostname.ifBlank { "Not set" })
            }
            if (settings.tunnelEnabled) {
                Text(tunnelStatusText(state), style = MaterialTheme.typography.bodyLarge)
                val url = when (settings.tunnelMode) {
                    TunnelMode.QUICK -> quickUrl
                    TunnelMode.TOKEN -> settings.tunnelHostname.takeIf { it.isNotBlank() }
                        ?.let { "https://${it.removePrefix("https://")}" }
                }
                if (url != null && state is TunnelState.Connected) {
                    SelectionContainer { Text(url, fontFamily = FontFamily.Monospace) }
                }
            }
            if (settings.tunnelMode == TunnelMode.TOKEN && hasToken) {
                TextButton(onClick = { confirmRemoveToken = true }) {
                    Text("Remove token", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        edit = {
            SwitchRow(
                "Enable tunnel",
                enabled,
                { enabled = it },
                detail = "Runs while the server is running.",
            )
            Column {
                RadioOption(
                    selected = mode == TunnelMode.TOKEN,
                    title = "My Cloudflare tunnel",
                    detail = "Uses a tunnel token from the Cloudflare dashboard. Your own hostname, which never changes.",
                ) { mode = TunnelMode.TOKEN }
                RadioOption(
                    selected = mode == TunnelMode.QUICK,
                    title = "Quick tunnel (for testing)",
                    detail = "No account needed. Gets a random trycloudflare.com URL that changes every time it starts.",
                ) { mode = TunnelMode.QUICK }
            }
            if (mode == TunnelMode.TOKEN) {
                Text(
                    "In Cloudflare Zero Trust, go to Networks > Tunnels, create a tunnel, and copy its token. " +
                        "Add a public hostname with the service set to http://localhost:$port.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it.trim() },
                    label = { Text(if (hasToken) "Tunnel token (saved, paste to replace)" else "Tunnel token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = hostname,
                    onValueChange = { hostname = it.trim() },
                    label = { Text("Public hostname (optional, for display)") },
                    placeholder = { Text("sms.example.com") },
                    singleLine = true,
                )
            }
        },
    )

    PendingChangeDialog(pending) { pending = null }
    if (confirmRemoveToken) {
        ConfirmDialog(
            title = "Remove the tunnel token?",
            text = if (settings.tunnelEnabled) {
                "The tunnel disconnects and the API is no longer reachable from the internet until you paste a token again."
            } else {
                "You'll need to paste the token again to use your Cloudflare tunnel."
            },
            confirmLabel = "Remove",
            onDismiss = { confirmRemoveToken = false },
            onConfirm = {
                confirmRemoveToken = false
                settings.tunnelToken = null
                tokenVersion++
                tunnel.reload()
            },
        )
    }
}

@Composable
private fun TunnelLogCard(app: GatewayApp) {
    val log by app.tunnel.log.collectAsStateWithLifecycle()
    var showLog by rememberSaveable { mutableStateOf(false) }
    Section(
        title = "Log",
        action = { TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide" else "Show") } },
    ) {
        if (showLog) {
            SelectionContainer {
                Text(
                    log.takeLast(40).joinToString("\n").ifEmpty { "Nothing yet." },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal fun tunnelStatusText(state: TunnelState): String = when (state) {
    TunnelState.Off -> "Starts when the server is running."
    TunnelState.NotBundled -> "cloudflared isn't included in this build."
    TunnelState.MissingToken -> "Paste a tunnel token to connect."
    TunnelState.WaitingForNetwork -> "Waiting for Wi-Fi or mobile data…"
    TunnelState.Connecting -> "Connecting to Cloudflare…"
    is TunnelState.Connected -> "Connected (${state.connections} connection${if (state.connections == 1) "" else "s"} to Cloudflare)"
    is TunnelState.Reconnecting -> "Reconnecting: ${state.reason}"
}
