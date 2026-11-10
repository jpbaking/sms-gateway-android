package io.github.jpbaking.smsgateway.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.TunnelMode
import io.github.jpbaking.smsgateway.tunnel.TunnelState

/** Settings and status for the built-in Cloudflare Tunnel, which makes the API reachable from the internet. */
@Composable
fun TunnelSection(app: GatewayApp, port: Int) {
    val tunnel = app.tunnel
    val settings = app.settings
    val state by tunnel.state.collectAsStateWithLifecycle()
    val quickUrl by tunnel.quickUrl.collectAsStateWithLifecycle()
    val log by tunnel.log.collectAsStateWithLifecycle()

    var enabled by remember { mutableStateOf(settings.tunnelEnabled) }
    var mode by remember { mutableStateOf(settings.tunnelMode) }
    var hasToken by remember { mutableStateOf(!settings.tunnelToken.isNullOrBlank()) }
    var tokenText by remember { mutableStateOf("") }
    var hostname by remember { mutableStateOf(settings.tunnelHostname) }
    var showLog by remember { mutableStateOf(false) }

    Section("Public access (Cloudflare Tunnel)") {
        if (!tunnel.isBundled) {
            Text(
                "This build doesn't include cloudflared for this phone's processor, so the tunnel isn't available.",
                color = MaterialTheme.colorScheme.error,
            )
            return@Section
        }
        Text(
            "Makes the API reachable from the internet without opening ports. Anyone with the URL " +
                "can reach the API, so keep your API keys secret.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable tunnel")
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                settings.tunnelEnabled = it
                tunnel.reload()
            })
        }

        Column {
            ModeOption(
                selected = mode == TunnelMode.TOKEN,
                title = "My Cloudflare tunnel",
                detail = "Uses a tunnel token from the Cloudflare dashboard. Your own hostname, which never changes.",
            ) {
                mode = TunnelMode.TOKEN
                settings.tunnelMode = mode
                tunnel.reload()
            }
            ModeOption(
                selected = mode == TunnelMode.QUICK,
                title = "Quick tunnel (for testing)",
                detail = "No account needed. Gets a random trycloudflare.com URL that changes every time it starts.",
            ) {
                mode = TunnelMode.QUICK
                settings.tunnelMode = mode
                tunnel.reload()
            }
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
            Row {
                Button(onClick = {
                    if (tokenText.isNotBlank()) {
                        settings.tunnelToken = tokenText
                        hasToken = true
                        tokenText = ""
                    }
                    settings.tunnelHostname = hostname
                    tunnel.reload()
                }) { Text("Save") }
                if (hasToken) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        settings.tunnelToken = null
                        hasToken = false
                        tunnel.reload()
                    }) { Text("Remove token", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        if (enabled) {
            Text(statusText(state), style = MaterialTheme.typography.bodyLarge)
            val url = when (mode) {
                TunnelMode.QUICK -> quickUrl
                TunnelMode.TOKEN -> hostname.takeIf { it.isNotBlank() }?.let { "https://${it.removePrefix("https://")}" }
            }
            if (url != null && state is TunnelState.Connected) {
                SelectionContainer { Text(url, fontFamily = FontFamily.Monospace) }
            }
        }

        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
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

@Composable
private fun ModeOption(selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
    Row(
        Modifier.selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun statusText(state: TunnelState): String = when (state) {
    TunnelState.Off -> "Starts when the server is running."
    TunnelState.NotBundled -> "cloudflared isn't included in this build."
    TunnelState.MissingToken -> "Paste a tunnel token to connect."
    TunnelState.WaitingForNetwork -> "Waiting for Wi-Fi or mobile data…"
    TunnelState.Connecting -> "Connecting to Cloudflare…"
    is TunnelState.Connected -> "Connected (${state.connections} connection${if (state.connections == 1) "" else "s"} to Cloudflare)"
    is TunnelState.Reconnecting -> "Reconnecting: ${state.reason}"
}
