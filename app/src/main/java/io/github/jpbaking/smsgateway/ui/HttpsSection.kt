package io.github.jpbaking.smsgateway.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.jpbaking.smsgateway.service.localIpv4Addresses
import io.github.jpbaking.smsgateway.tls.TlsMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat

/** HTTPS settings: on/off, port, and the certificate (self-signed, pasted PEM, or a PKCS#12 file). */
@Composable
fun HttpsSection(app: GatewayApp, running: Boolean) {
    val settings = app.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(settings.httpsEnabled) }
    var portText by remember { mutableStateOf(settings.httpsPort.toString()) }
    var httpOnLan by remember { mutableStateOf(settings.httpOnLan) }
    // Bumped after the certificate changes, to re-read it.
    var certVersion by remember { mutableIntStateOf(0) }
    val material = remember(certVersion) { settings.tlsMaterial() }
    val source = remember(certVersion) { settings.tlsSource }

    var mode by remember { mutableStateOf(source ?: TlsSource.SELF_SIGNED) }
    var names by remember { mutableStateOf((listOf("localhost") + localIpv4Addresses()).joinToString(", ")) }
    var certPem by remember { mutableStateOf("") }
    var keyPem by remember { mutableStateOf("") }
    var p12Uri by remember { mutableStateOf<Uri?>(null) }
    var p12Password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val pickP12 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) p12Uri = uri
    }

    fun save(newSource: TlsSource, build: () -> TlsMaterial) {
        error = null
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching(build) }
            result.onSuccess {
                settings.saveTls(it, newSource)
                certVersion++
                certPem = ""
                keyPem = ""
                p12Password = ""
                p12Uri = null
            }.onFailure { error = it.message ?: "Couldn't use that certificate" }
            busy = false
        }
    }

    val editable = !running && !busy

    Section("HTTPS") {
        Text(
            "Serves the API over HTTPS so API keys and messages are encrypted on the network.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (running) {
            Text("Stop the server to change these settings.", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Serve HTTPS")
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, enabled = editable, onCheckedChange = {
                enabled = it
                settings.httpsEnabled = it
            })
        }
        if (!enabled) return@Section

        OutlinedTextField(
            value = portText,
            onValueChange = { v ->
                portText = v.filter(Char::isDigit).take(5)
                portText.toIntOrNull()
                    ?.takeIf { it in 1024..65535 && it != settings.port }
                    ?.let { settings.httpsPort = it }
            },
            label = { Text("HTTPS port") },
            enabled = editable,
            isError = portText.toIntOrNull()?.let { it !in 1024..65535 || it == settings.port } ?: true,
            supportingText = { Text("1024 to 65535, different from the HTTP port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Also allow plain HTTP from other devices")
                Text(
                    "When off, plain HTTP only answers on this phone (the Cloudflare Tunnel still works).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = httpOnLan, enabled = editable, onCheckedChange = {
                httpOnLan = it
                settings.httpOnLan = it
            })
        }

        if (material == null) {
            Text("No certificate yet. Set one up below.", color = MaterialTheme.colorScheme.error)
        } else {
            CertificateSummary(material, source)
        }

        Text(if (material == null) "Certificate" else "Replace certificate", style = MaterialTheme.typography.titleSmall)
        Column {
            SourceOption(mode == TlsSource.SELF_SIGNED, "Self-signed", "Made on this phone. Clients must trust or pin it.") {
                mode = TlsSource.SELF_SIGNED
            }
            SourceOption(mode == TlsSource.PEM, "Paste certificate and key", "PEM text, e.g. from Let's Encrypt.") {
                mode = TlsSource.PEM
            }
            SourceOption(mode == TlsSource.PKCS12, "PKCS#12 file", "A .p12 or .pfx file and its password.") {
                mode = TlsSource.PKCS12
            }
        }

        when (mode) {
            TlsSource.SELF_SIGNED -> {
                OutlinedTextField(
                    value = names,
                    onValueChange = { names = it },
                    label = { Text("Host names and IP addresses") },
                    supportingText = { Text("Comma-separated. Clients must connect using one of these.") },
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(enabled = editable, onClick = {
                    save(TlsSource.SELF_SIGNED) { TlsMaterial.selfSigned(names.split(',', ' ', '\n')) }
                }) { Text(if (material == null) "Generate certificate" else "Generate new certificate") }
            }

            TlsSource.PEM -> {
                OutlinedTextField(
                    value = certPem,
                    onValueChange = { certPem = it },
                    label = { Text("Certificate (PEM, with any intermediates)") },
                    placeholder = { Text("-----BEGIN CERTIFICATE-----") },
                    enabled = editable,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                )
                OutlinedTextField(
                    value = keyPem,
                    onValueChange = { keyPem = it },
                    label = { Text("Private key (PEM, unencrypted)") },
                    placeholder = { Text("-----BEGIN PRIVATE KEY-----") },
                    enabled = editable,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
                )
                Button(enabled = editable && certPem.isNotBlank() && keyPem.isNotBlank(), onClick = {
                    val text = certPem + "\n" + keyPem
                    save(TlsSource.PEM) { TlsMaterial.fromPem(text) }
                }) { Text("Save") }
            }

            TlsSource.PKCS12 -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = editable, onClick = { pickP12.launch(arrayOf("*/*")) }) {
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
                    enabled = editable,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Button(enabled = editable && p12Uri != null, onClick = {
                    val uri = p12Uri ?: return@Button
                    val password = p12Password
                    save(TlsSource.PKCS12) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                            input.readBytes().also { require(it.size <= MAX_P12_BYTES) { "That file is too big for a PKCS#12 file" } }
                        } ?: throw IllegalArgumentException("Couldn't read that file")
                        TlsMaterial.fromPkcs12(bytes, password)
                    }
                }) { Text("Import") }
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (material != null) {
            TextButton(enabled = editable, onClick = {
                settings.clearTls()
                certVersion++
            }) { Text("Remove certificate", color = MaterialTheme.colorScheme.error) }
        }
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

@Composable
private fun SourceOption(selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
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
