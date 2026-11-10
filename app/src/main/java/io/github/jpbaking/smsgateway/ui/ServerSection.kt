package io.github.jpbaking.smsgateway.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.TlsSource
import io.github.jpbaking.smsgateway.data.TunnelMode
import io.github.jpbaking.smsgateway.net.ListenMode
import io.github.jpbaking.smsgateway.service.GatewayService
import io.github.jpbaking.smsgateway.service.localIpv4Addresses
import io.github.jpbaking.smsgateway.tls.TlsMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat

/**
 * The Server page: ports, start on boot and HTTPS in one card, so HTTP and HTTPS are edited (and
 * restarted) together, then the HTTPS certificate.
 */
@Composable
fun ServerSection(app: GatewayApp, running: Boolean) {
    // Bumped after the certificate changes, to re-read it.
    var certVersion by remember { mutableIntStateOf(0) }
    val material = remember(certVersion) { app.settings.tlsMaterial() }
    val source = remember(certVersion) { app.settings.tlsSource }

    ServerOptions(app, running, hasCertificate = material != null)
    CertificateCard(app, running, material, source, onChanged = { certVersion++ })
}

/** Why plain HTTP stays on with HTTPS, and who can reach it. */
@Composable
private fun PlainHttpExplainer(httpOnLan: Boolean) {
    Text(
        "Plain HTTP is still needed with HTTPS on: HTTPS and the Cloudflare Tunnel connect to it on this " +
            "phone. " + if (httpOnLan) {
                "Because \"Also allow plain HTTP from other devices\" is on, other devices can reach it too."
            } else {
                "Other devices can't reach it unless \"Also allow plain HTTP from other devices\" is on."
            },
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ServerOptions(app: GatewayApp, running: Boolean, hasCertificate: Boolean) {
    val context = LocalContext.current
    val settings = app.settings
    var editing by rememberSaveable { mutableStateOf(false) }
    var port by rememberSaveable { mutableStateOf(settings.port.toString()) }
    var autoStart by rememberSaveable { mutableStateOf(settings.autoStart) }
    var httpsEnabled by rememberSaveable { mutableStateOf(settings.httpsEnabled) }
    var httpsPort by rememberSaveable { mutableStateOf(settings.httpsPort.toString()) }
    var httpOnLan by rememberSaveable { mutableStateOf(settings.httpOnLan) }
    var listenMode by rememberSaveable { mutableStateOf(settings.listenMode) }
    var allowTailscale by rememberSaveable { mutableStateOf(settings.allowTailscale) }
    var pending by remember { mutableStateOf<PendingChange?>(null) }

    fun reset() {
        listenMode = settings.listenMode
        allowTailscale = settings.allowTailscale
        port = settings.port.toString()
        autoStart = settings.autoStart
        httpsEnabled = settings.httpsEnabled
        httpsPort = settings.httpsPort.toString()
        httpOnLan = settings.httpOnLan
    }

    EditableSection(
        title = "Server",
        editing = editing,
        onEditingChange = { editing = it },
        dirty = port != settings.port.toString() ||
            autoStart != settings.autoStart ||
            httpsEnabled != settings.httpsEnabled ||
            httpsPort != settings.httpsPort.toString() ||
            httpOnLan != settings.httpOnLan ||
            listenMode != settings.listenMode ||
            allowTailscale != settings.allowTailscale,
        onDiscard = ::reset,
        onSave = save@{
            val newPort = port.toIntOrNull()?.takeIf { it in 1024..65535 }
                ?: return@save "The HTTP port must be between 1024 and 65535."
            val newHttpsPort = if (httpsEnabled) {
                httpsPort.toIntOrNull()?.takeIf { it in 1024..65535 }
                    ?: return@save "The HTTPS port must be between 1024 and 65535."
            } else {
                settings.httpsPort
            }
            if (httpsEnabled && newHttpsPort == newPort) return@save "HTTP and HTTPS need different ports."
            if (httpsEnabled && !hasCertificate) return@save "Set up a certificate below before turning HTTPS on."

            // What changes for clients, for the confirmation.
            val effects = buildList {
                if (listenMode != settings.listenMode) {
                    add(
                        when (listenMode) {
                            ListenMode.TUNNEL_ONLY -> "Only the Cloudflare Tunnel can reach the server. Devices on " +
                                "Wi-Fi lose access, and HTTPS isn't served."
                            ListenMode.LAN -> "Only devices on the local network can connect, plus the Cloudflare " +
                                "Tunnel. Connections over mobile data or from public addresses are refused."
                            ListenMode.ANYWHERE -> "Anything that can reach this phone can connect, which can " +
                                "include the internet. Only the API key protects it."
                        },
                    )
                }
                if (allowTailscale != settings.allowTailscale && listenMode == ListenMode.LAN) {
                    add(
                        if (allowTailscale) {
                            "Addresses in 100.64.0.0/10 (Tailscale) are let in."
                        } else {
                            "Addresses in 100.64.0.0/10 (Tailscale) are refused."
                        },
                    )
                }
                if (newPort != settings.port) {
                    add("Plain HTTP moves from port ${settings.port} to $newPort.")
                    if (settings.tunnelEnabled && settings.tunnelMode == TunnelMode.TOKEN) {
                        add("In the Cloudflare dashboard, point your tunnel at http://localhost:$newPort.")
                    }
                }
                if (httpsEnabled && !settings.httpsEnabled) add("HTTPS starts on port $newHttpsPort.")
                if (!httpsEnabled && settings.httpsEnabled) add("HTTPS stops; clients using https:// lose access.")
                if (httpsEnabled && settings.httpsEnabled && newHttpsPort != settings.httpsPort) {
                    add("HTTPS moves from port ${settings.httpsPort} to $newHttpsPort.")
                }
                val lanBefore = !settings.httpsEnabled || settings.httpOnLan
                val lanAfter = !httpsEnabled || httpOnLan
                if (lanBefore && !lanAfter) add("Plain HTTP stops answering other devices (the tunnel still works).")
                if (!lanBefore && lanAfter) add("Plain HTTP answers other devices again.")
            }
            val commit = {
                settings.port = newPort
                settings.autoStart = autoStart
                settings.httpsEnabled = httpsEnabled
                settings.httpsPort = newHttpsPort
                settings.httpOnLan = httpOnLan
                settings.listenMode = listenMode
                settings.allowTailscale = allowTailscale
                if (running && effects.isNotEmpty()) GatewayService.restart(context)
                editing = false
                reset()
            }
            if (running && effects.isNotEmpty()) {
                pending = PendingChange(
                    title = "Restart the server?",
                    text = (effects + "The server restarts to apply this.").joinToString("\n\n"),
                    confirmLabel = "Restart",
                    commit = commit,
                )
            } else {
                commit()
            }
            null
        },
        view = {
            ValueRow("Who can connect", settings.listenMode.label)
            Text(settings.listenMode.detail, style = MaterialTheme.typography.bodySmall)
            if (settings.listenMode == ListenMode.LAN) {
                ValueRow("Tailscale addresses", if (settings.allowTailscale) "Allowed" else "Refused")
            }
            HorizontalDivider()
            ValueRow("HTTP port", settings.port.toString(), monospace = true)
            if (settings.httpsEnabled && settings.listenMode != ListenMode.TUNNEL_ONLY) {
                ValueRow("Plain HTTP from other devices", if (settings.httpOnLan) "Allowed" else "Blocked")
                PlainHttpExplainer(settings.httpOnLan)
            }
            ValueRow("Start on boot", onOff(settings.autoStart))
            HorizontalDivider()
            ValueRow("Serve HTTPS", onOff(settings.httpsEnabled))
            if (settings.httpsEnabled && settings.listenMode == ListenMode.TUNNEL_ONLY) {
                Text(
                    "Not served while only the tunnel can connect: Cloudflare already encrypts the tunnel.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (settings.httpsEnabled) {
                ValueRow("HTTPS port", settings.httpsPort.toString(), monospace = true)
            } else {
                Text(
                    "Turn on HTTPS to encrypt API keys and messages on the network.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        edit = {
            Text("Who can connect", style = MaterialTheme.typography.titleSmall)
            Column {
                ListenMode.entries.forEach { m ->
                    RadioOption(selected = listenMode == m, title = m.label, detail = m.detail) { listenMode = m }
                }
            }
            if (listenMode == ListenMode.LAN) {
                SwitchRow(
                    "Also allow Tailscale addresses",
                    allowTailscale,
                    { allowTailscale = it },
                    detail = "100.64.0.0/10. Leave off unless you use Tailscale: mobile carriers use the same range.",
                )
            }
            HorizontalDivider()
            OutlinedTextField(
                value = port,
                onValueChange = { port = digits(it) },
                label = { Text("HTTP port") },
                supportingText = { Text("1024 to 65535") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            if (httpsEnabled) {
                SwitchRow(
                    "Also allow plain HTTP from other devices",
                    httpOnLan,
                    { httpOnLan = it },
                )
                PlainHttpExplainer(httpOnLan)
            }
            SwitchRow(
                "Start on boot",
                autoStart,
                { autoStart = it },
                detail = "Brings the server back after a reboot or app update.",
            )
            HorizontalDivider()
            SwitchRow(
                "Serve HTTPS",
                httpsEnabled,
                { httpsEnabled = it },
                detail = "Encrypts API keys and messages on the network.",
            )
            if (httpsEnabled) {
                OutlinedTextField(
                    value = httpsPort,
                    onValueChange = { httpsPort = digits(it) },
                    label = { Text("HTTPS port") },
                    supportingText = { Text("1024 to 65535, different from the HTTP port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
    )
    PendingChangeDialog(pending) { pending = null }
}

@Composable
private fun CertificateCard(
    app: GatewayApp,
    running: Boolean,
    material: TlsMaterial?,
    source: TlsSource?,
    onChanged: () -> Unit,
) {
    val settings = app.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editing by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(source ?: TlsSource.SELF_SIGNED) }
    var names by rememberSaveable { mutableStateOf((listOf("localhost") + localIpv4Addresses()).joinToString(", ")) }
    var certPem by remember { mutableStateOf("") }
    var keyPem by remember { mutableStateOf("") }
    var p12Uri by remember { mutableStateOf<Uri?>(null) }
    var p12Password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingChange?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    val pickP12 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) p12Uri = uri
    }

    fun close() {
        editing = false
        error = null
        certPem = ""
        keyPem = ""
        p12Password = ""
        p12Uri = null
    }

    val dirty = certPem.isNotBlank() || keyPem.isNotBlank() || p12Uri != null || p12Password.isNotEmpty()
    fun cancel() {
        if (dirty) confirmDiscard = true else close()
    }
    BackHandler(enabled = editing && !busy) { cancel() }

    // In use right now, so replacing it restarts the server.
    val inUse = running && settings.httpsEnabled

    fun use(newSource: TlsSource, build: () -> TlsMaterial) {
        error = null
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching(build) }
            busy = false
            result.onSuccess { built ->
                val commit = {
                    settings.saveTls(built, newSource)
                    if (inUse) GatewayService.restart(context)
                    onChanged()
                    close()
                }
                if (inUse) {
                    pending = PendingChange(
                        title = "Use the new certificate?",
                        text = "The server restarts to use it. Clients that trust or pin the old certificate " +
                            "need the new one.",
                        confirmLabel = "Restart",
                        commit = commit,
                    )
                } else {
                    commit()
                }
            }.onFailure { error = it.message ?: "Couldn't use that certificate" }
        }
    }

    Section(
        title = "Certificate",
        action = if (editing) {
            null
        } else {
            { TextButton(onClick = { editing = true }) { Text(if (material == null) "Set up" else "Replace") } }
        },
    ) {
        if (!editing) {
            if (material == null) {
                Text(
                    "No certificate yet.",
                    color = if (settings.httpsEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            } else {
                CertificateSummary(material, source)
                if (settings.httpsEnabled) {
                    Text("Turn HTTPS off to remove the certificate.", style = MaterialTheme.typography.bodySmall)
                } else {
                    TextButton(onClick = { confirmRemove = true }) {
                        Text("Remove certificate", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            return@Section
        }

        Column {
            RadioOption(mode == TlsSource.SELF_SIGNED, "Self-signed", "Made on this phone. Clients must trust or pin it.") {
                mode = TlsSource.SELF_SIGNED
            }
            RadioOption(mode == TlsSource.PEM, "Paste certificate and key", "PEM text, e.g. from Let's Encrypt.") {
                mode = TlsSource.PEM
            }
            RadioOption(mode == TlsSource.PKCS12, "PKCS#12 file", "A .p12 or .pfx file and its password.") {
                mode = TlsSource.PKCS12
            }
        }

        val canUse = !busy && when (mode) {
            TlsSource.SELF_SIGNED -> names.isNotBlank()
            TlsSource.PEM -> certPem.isNotBlank() && keyPem.isNotBlank()
            TlsSource.PKCS12 -> p12Uri != null
        }

        when (mode) {
            TlsSource.SELF_SIGNED -> OutlinedTextField(
                value = names,
                onValueChange = { names = it },
                label = { Text("Host names and IP addresses") },
                supportingText = { Text("Comma-separated. Clients must connect using one of these.") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )

            TlsSource.PEM -> {
                OutlinedTextField(
                    value = certPem,
                    onValueChange = { certPem = it },
                    label = { Text("Certificate (PEM, with any intermediates)") },
                    placeholder = { Text("-----BEGIN CERTIFICATE-----") },
                    enabled = !busy,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                )
                OutlinedTextField(
                    value = keyPem,
                    onValueChange = { keyPem = it },
                    label = { Text("Private key (PEM, unencrypted)") },
                    placeholder = { Text("-----BEGIN PRIVATE KEY-----") },
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
                )
            }

            TlsSource.PKCS12 -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = !busy, onClick = { pickP12.launch(arrayOf("*/*")) }) {
                        Text(if (p12Uri == null) "Choose file" else "Choose another file")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        p12Uri?.lastPathSegment?.substringAfterLast('/') ?: "No file chosen",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
                OutlinedTextField(
                    value = p12Password,
                    onValueChange = { p12Password = it },
                    label = { Text("File password") },
                    enabled = !busy,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (busy) Text("Working…", style = MaterialTheme.typography.bodySmall)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(enabled = !busy, onClick = { cancel() }) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(enabled = canUse, onClick = {
                when (mode) {
                    TlsSource.SELF_SIGNED -> {
                        val list = names.split(',', ' ', '\n')
                        use(TlsSource.SELF_SIGNED) { TlsMaterial.selfSigned(list) }
                    }
                    TlsSource.PEM -> {
                        val text = certPem + "\n" + keyPem
                        use(TlsSource.PEM) { TlsMaterial.fromPem(text) }
                    }
                    TlsSource.PKCS12 -> {
                        val uri = p12Uri ?: return@Button
                        val password = p12Password
                        use(TlsSource.PKCS12) {
                            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                                input.readBytes().also {
                                    require(it.size <= MAX_P12_BYTES) { "That file is too big for a PKCS#12 file" }
                                }
                            } ?: throw IllegalArgumentException("Couldn't read that file")
                            TlsMaterial.fromPkcs12(bytes, password)
                        }
                    }
                }
            }) {
                Text(
                    when (mode) {
                        TlsSource.SELF_SIGNED -> "Generate"
                        TlsSource.PEM -> "Save"
                        TlsSource.PKCS12 -> "Import"
                    },
                )
            }
        }
    }

    PendingChangeDialog(pending) { pending = null }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            text = "The certificate you entered hasn't been saved.",
            confirmLabel = "Discard",
            onDismiss = { confirmDiscard = false },
            onConfirm = {
                confirmDiscard = false
                close()
            },
        )
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove the certificate?",
            text = "Its private key is deleted from this phone. To turn HTTPS on again you'll need to set up a certificate.",
            confirmLabel = "Remove",
            onDismiss = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                settings.clearTls()
                onChanged()
            },
        )
    }
}

private const val MAX_P12_BYTES = 1 shl 20

@Composable
private fun CertificateSummary(material: TlsMaterial, source: TlsSource?) {
    val leaf = material.leaf
    val expired = leaf.notAfter.time < System.currentTimeMillis()
    Column {
        Text(source?.label ?: "Certificate", style = MaterialTheme.typography.bodyMedium)
        Text(leaf.subjectX500Principal.name, style = MaterialTheme.typography.bodySmall)
        if (material.names.isNotEmpty()) {
            Text("Valid for: ${material.names.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            (if (expired) "Expired " else "Expires ") +
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(leaf.notAfter),
            style = MaterialTheme.typography.bodySmall,
            color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text("SHA-256 fingerprint", style = MaterialTheme.typography.labelSmall)
        SelectionContainer {
            Text(material.sha256Fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
