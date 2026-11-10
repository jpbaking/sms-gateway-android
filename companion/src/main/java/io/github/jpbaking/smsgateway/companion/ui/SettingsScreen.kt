package io.github.jpbaking.smsgateway.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jpbaking.smsgateway.companion.BuildConfig
import io.github.jpbaking.smsgateway.companion.CompanionApp
import io.github.jpbaking.smsgateway.companion.R
import io.github.jpbaking.smsgateway.companion.api.GatewayClient
import io.github.jpbaking.smsgateway.companion.api.GatewayConfig
import io.github.jpbaking.smsgateway.companion.api.GatewayUrl
import io.github.jpbaking.smsgateway.companion.api.Tls
import io.github.jpbaking.smsgateway.companion.api.isCertificateProblem
import kotlinx.coroutines.launch

private data class CertOffer(val fingerprint: String, val subject: String, val expires: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: CompanionApp, back: () -> Unit) {
    val saved = app.settings.config.value
    var url by rememberSaveable { mutableStateOf(saved?.baseUrl.orEmpty()) }
    var apiKey by rememberSaveable { mutableStateOf(saved?.apiKey.orEmpty()) }
    var pin by rememberSaveable { mutableStateOf(saved?.pinnedSha256.orEmpty()) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var canSaveAnyway by remember { mutableStateOf(false) }
    var offer by remember { mutableStateOf<CertOffer?>(null) }
    val scope = rememberCoroutineScope()

    fun buildConfig(): GatewayConfig? {
        val base = GatewayUrl.normalize(url)
        if (base == null) {
            error = "Enter the gateway's URL, e.g. https://sms.example.com or http://192.168.1.50:8080"
            return null
        }
        if (apiKey.isBlank()) {
            error = "Enter an API key. Create one in the gateway app under Settings → API keys."
            return null
        }
        val p = pin.takeIf { it.isNotBlank() }?.let {
            Tls.normalizeFingerprint(it) ?: run {
                error = "The fingerprint should be 64 hex digits (SHA-256), as the gateway shows it."
                return null
            }
        }
        return GatewayConfig(base, apiKey.trim(), p)
    }

    fun save(cfg: GatewayConfig) {
        if (saved == null || saved.baseUrl != cfg.baseUrl) app.readState.reset()
        app.settings.save(cfg)
        back()
    }

    fun testAndSave() {
        val cfg = buildConfig() ?: return
        url = cfg.baseUrl
        error = null
        canSaveAnyway = false
        scope.launch {
            busy = true
            try {
                val client = GatewayClient(cfg)
                client.sims() // Needs the key, so this checks the key as well as the address.
                save(cfg)
            } catch (e: Exception) {
                if (e.isCertificateProblem() && cfg.baseUrl.startsWith("https://")) {
                    runCatching { GatewayClient.fetchCertificate(cfg.baseUrl) }
                        .onSuccess { cert ->
                            offer = CertOffer(
                                Tls.fingerprint(cert),
                                cert.subjectX500Principal.name,
                                java.text.DateFormat.getDateInstance().format(cert.notAfter),
                            )
                        }
                        .onFailure { error = "The certificate isn't trusted, and couldn't be read: ${it.message}" }
                } else {
                    error = e.message ?: e.javaClass.simpleName
                    canSaveAnyway = true
                }
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (saved != null) {
                        IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
                    }
                },
                title = { Text("Gateway") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "This app shows and sends texts through your SMS Gateway phone. It doesn't read or send " +
                    "this phone's own SMS.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Gateway URL") },
                placeholder = { Text("https://sms.example.com") },
                supportingText = { Text("Shown on the gateway's Status page, or your Cloudflare Tunnel address.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (GatewayUrl.normalize(url)?.let(GatewayUrl::isUnencrypted) == true) {
                Text(
                    "This address isn't encrypted (http://). Anyone on the same network can read the API key " +
                        "and your messages. Turn on HTTPS in the gateway app (Settings → Server) and use the " +
                        "https:// address, or only use this on a network you trust.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                supportingText = { Text("Give this phone its own key in the gateway app, so you can revoke it alone.") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(),
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Self-signed certificate", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Only for a gateway serving HTTPS with its own self-signed certificate. Paste the SHA-256 " +
                            "fingerprint from the gateway's Settings → Server → Certificate, or leave it empty: if " +
                            "the certificate isn't trusted, Save shows its fingerprint to compare and trust.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = { Text("Trusted fingerprint (SHA-256)") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (pin.isNotBlank()) {
                        TextButton(onClick = { pin = "" }) { Text("Stop trusting it") }
                    }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::testAndSave, enabled = !busy) { Text(if (busy) "Connecting…" else "Save") }
                if (canSaveAnyway) {
                    OutlinedButton(onClick = { buildConfig()?.let(::save) }, enabled = !busy) { Text("Save anyway") }
                }
            }

            Text(
                "For live updates, turn on Settings → Event stream in the gateway app. Without it, this app " +
                    "refreshes every 15 seconds while it's open. Nothing is fetched while the app is closed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    offer?.let { o ->
        AlertDialog(
            onDismissRequest = { offer = null },
            title = { Text("Trust this certificate?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The gateway's certificate isn't signed by a known authority. Trust it only if this fingerprint matches the one in the gateway app (Settings → Server → Certificate).")
                    Text(o.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("${o.subject}\nValid until ${o.expires}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pin = o.fingerprint
                    offer = null
                    testAndSave()
                }) { Text("Trust") }
            },
            dismissButton = { TextButton(onClick = { offer = null }) { Text("Cancel") } },
        )
    }
}
