package io.github.jpbaking.smsgateway.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jpbaking.smsgateway.BuildConfig
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.Direction
import io.github.jpbaking.smsgateway.data.MessageEntity
import io.github.jpbaking.smsgateway.service.GatewayService
import io.github.jpbaking.smsgateway.service.ServerState
import io.github.jpbaking.smsgateway.service.localIpv4Addresses
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as GatewayApp
        setContent {
            val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicLightColorScheme(LocalContext.current)
            } else {
                lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) { GatewayScreen(app) }
        }
    }
}

private val requiredPermissions: Array<String> = buildList {
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.RECEIVE_SMS)
    add(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GatewayScreen(app: GatewayApp) {
    val context = LocalContext.current
    // Bumped whenever something outside Compose may have changed (permissions, settings).
    var refresh by remember { mutableIntStateOf(0) }

    val missing = remember(refresh) {
        requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh++ }

    val state by GatewayService.state.collectAsStateWithLifecycle()
    val messages by remember { app.db.messages().observeRecent(50) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val sims = remember(refresh) { app.sims.activeSims() }
    var portText by remember { mutableStateOf(app.settings.port.toString()) }
    var autoStart by remember { mutableStateOf(app.settings.autoStart) }

    Scaffold(topBar = { TopAppBar(title = { Text("SMS Gateway") }) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (missing.isNotEmpty()) {
                item {
                    Section("Permissions needed") {
                        Text("The gateway needs SMS and phone permissions to send, receive and pick a SIM.")
                        Button(onClick = { permissionLauncher.launch(requiredPermissions) }) {
                            Text("Grant permissions")
                        }
                    }
                }
            }

            item {
                Section("Server") {
                    val running = state is ServerState.Running
                    Text(
                        when (val s = state) {
                            is ServerState.Running -> "Running on port ${s.port}"
                            is ServerState.Error -> s.message
                            ServerState.Stopped -> "Stopped"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (running) {
                        val port = (state as ServerState.Running).port
                        SelectionContainer {
                            Column {
                                localIpv4Addresses().forEach {
                                    Text("http://$it:$port", fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        enabled = !running,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (running) {
                            Button(onClick = { GatewayService.stop(context) }) { Text("Stop") }
                        } else {
                            Button(onClick = {
                                val port = portText.toIntOrNull()?.takeIf { it in 1024..65535 }
                                if (port != null) {
                                    app.settings.port = port
                                    GatewayService.start(context)
                                } else {
                                    portText = app.settings.port.toString()
                                }
                            }) { Text("Start") }
                        }
                        Spacer(Modifier.width(16.dp))
                        Text("Start on boot")
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = autoStart, onCheckedChange = {
                            autoStart = it
                            app.settings.autoStart = it
                        })
                    }
                    BatteryOptimizationRow()
                }
            }

            item { ApiKeysSection(app) }

            item { WebhookRetrySection(app) }

            item {
                Section("SIM cards") {
                    if (sims.isEmpty()) Text("No active SIMs found (or phone permission not granted).")
                    sims.forEach { sim ->
                        Text(
                            "SIM ${sim.slot + 1}: ${sim.carrierName ?: sim.displayName ?: "Unknown"}" +
                                (sim.number?.let { " · $it" } ?: "") +
                                (if (sim.isDefaultSms) " · default" else "") +
                                "  (simSlot=${sim.slot}, subscriptionId=${sim.subscriptionId})",
                        )
                    }
                }
            }

            item { Text("Recent messages", style = MaterialTheme.typography.titleMedium) }
            if (messages.isEmpty()) item { Text("Nothing yet.") }
            items(messages, key = { it.id }) { MessageRow(it) }

            item {
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WebhookRetrySection(app: GatewayApp) {
    var interval by remember { mutableStateOf(app.settings.webhookRetryIntervalSeconds.toString()) }
    var maxHours by remember { mutableStateOf(app.settings.webhookRetryMaxHours.toString()) }
    Section("Webhook retries") {
        Text(
            "If a webhook fails, it's retried after 10s, 20s, 40s, ... and then at this interval.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = interval,
            onValueChange = { v ->
                interval = v.filter(Char::isDigit).take(5)
                interval.toIntOrNull()?.takeIf { it >= 10 }?.let { app.settings.webhookRetryIntervalSeconds = it }
            },
            label = { Text("Retry interval (seconds, min 10)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = maxHours,
            onValueChange = { v ->
                maxHours = v.filter(Char::isDigit).take(5)
                app.settings.webhookRetryMaxHours = maxHours.toIntOrNull() ?: 0
            },
            label = { Text("Give up after (hours, 0 = never)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun BatteryOptimizationRow() {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    var ignoring by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    if (!ignoring) {
        Text(
            "Battery optimization may pause the server when the screen is off.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}")),
            )
            ignoring = true
        }) { Text("Allow running in background") }
    }
}

@Composable
private fun MessageRow(m: MessageEntity) {
    Column(Modifier.fillMaxWidth()) {
        val arrow = if (m.direction == Direction.IN) "← from" else "→ to"
        val sim = if (m.simSlot >= 0) "SIM ${m.simSlot + 1}" else "SIM ?"
        Text(
            "$arrow ${m.phoneNumber} · $sim · ${m.status.name.lowercase()}",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(m.body, maxLines = 3)
        m.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text(
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(m.createdAt)),
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
