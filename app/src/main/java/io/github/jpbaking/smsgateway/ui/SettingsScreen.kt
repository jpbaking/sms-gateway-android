package io.github.jpbaking.smsgateway.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jpbaking.smsgateway.BuildConfig
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.R
import io.github.jpbaking.smsgateway.data.Settings
import io.github.jpbaking.smsgateway.maintenance.CleanupWorker
import io.github.jpbaking.smsgateway.server.EventStream
import io.github.jpbaking.smsgateway.service.GatewayService
import io.github.jpbaking.smsgateway.service.ServerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

internal enum class SettingsPage(val title: String, val summary: String) {
    SERVER("Server", "Who can connect, ports, start on boot, HTTPS"),
    TUNNEL("Public access", "Cloudflare Tunnel"),
    API_KEYS("API keys", "Who may use the API, and what each key may do"),
    WEBHOOKS("Webhooks", "Where messages are forwarded, and retries"),
    EVENT_STREAM("Event stream", "Live events for client apps (SSE)"),
    STORAGE("Storage", "How long messages are kept"),
    ABOUT("About", "Version"),
}

@Composable
internal fun SettingsList(padding: PaddingValues, open: (SettingsPage) -> Unit) {
    ScreenColumn(padding) {
        Card(Modifier.fillMaxWidth()) {
            SettingsPage.entries.forEachIndexed { i, page ->
                if (i > 0) HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { open(page) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(page.title, style = MaterialTheme.typography.titleMedium)
                        Text(page.summary, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
                }
            }
        }
    }
}

@Composable
internal fun SettingsPageContent(app: GatewayApp, page: SettingsPage, padding: PaddingValues) {
    val state by GatewayService.state.collectAsStateWithLifecycle()
    val running = state is ServerState.Running
    ScreenColumn(padding) {
        when (page) {
            SettingsPage.SERVER -> ServerSection(app, running)
            SettingsPage.TUNNEL -> TunnelSection(app, (state as? ServerState.Running)?.port ?: app.settings.port)
            SettingsPage.API_KEYS -> ApiKeysSection(app)
            SettingsPage.WEBHOOKS -> {
                WebhooksSection(app)
                WebhookRetrySettings(app)
            }
            SettingsPage.EVENT_STREAM -> EventStreamSettings(app)
            SettingsPage.STORAGE -> StorageSettings(app)
            SettingsPage.ABOUT -> Section("About") {
                ValueRow("Version", BuildConfig.VERSION_NAME)
                ValueRow("App ID", BuildConfig.APPLICATION_ID, monospace = true)
            }
        }
    }
}

/** A change waiting for the user to confirm it in a dialog, e.g. one that restarts the server. */
internal class PendingChange(val title: String, val text: String, val confirmLabel: String, val commit: () -> Unit)

@Composable
internal fun PendingChangeDialog(pending: PendingChange?, onDismiss: () -> Unit) {
    pending ?: return
    ConfirmDialog(
        title = pending.title,
        text = pending.text,
        confirmLabel = pending.confirmLabel,
        destructive = false,
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            pending.commit()
        },
    )
}

@Composable
private fun WebhookRetrySettings(app: GatewayApp) {
    val settings = app.settings
    var editing by rememberSaveable { mutableStateOf(false) }
    var interval by rememberSaveable { mutableStateOf(settings.webhookRetryIntervalSeconds.toString()) }
    var maxHours by rememberSaveable { mutableStateOf(settings.webhookRetryMaxHours.toString()) }

    fun reset() {
        interval = settings.webhookRetryIntervalSeconds.toString()
        maxHours = settings.webhookRetryMaxHours.toString()
    }

    EditableSection(
        title = "Webhook retries",
        editing = editing,
        onEditingChange = { editing = it },
        dirty = interval != settings.webhookRetryIntervalSeconds.toString() ||
            maxHours != settings.webhookRetryMaxHours.toString(),
        onDiscard = ::reset,
        onSave = save@{
            val newInterval = interval.toIntOrNull()?.takeIf { it >= 10 }
                ?: return@save "The retry interval must be at least 10 seconds."
            val newMax = maxHours.toIntOrNull()?.takeIf { it in 1..Settings.MAX_RETRY_MAX_HOURS }
                ?: return@save "Give up after 1 to ${Settings.MAX_RETRY_MAX_HOURS} hours (30 days)."
            settings.webhookRetryIntervalSeconds = newInterval
            settings.webhookRetryMaxHours = newMax
            editing = false
            null
        },
        view = {
            Text(
                "A failed webhook is retried after 10s, 20s, 40s, ... and then at this interval.",
                style = MaterialTheme.typography.bodySmall,
            )
            ValueRow("Retry interval", "${settings.webhookRetryIntervalSeconds} s")
            ValueRow("Give up after", "${settings.webhookRetryMaxHours} h")
        },
        edit = {
            OutlinedTextField(
                value = interval,
                onValueChange = { interval = digits(it) },
                label = { Text("Retry interval (seconds, min 10)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = maxHours,
                onValueChange = { maxHours = digits(it) },
                label = { Text("Give up after (hours, 1 to ${Settings.MAX_RETRY_MAX_HOURS})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
    )
}

@Composable
private fun EventStreamSettings(app: GatewayApp) {
    val stream = app.events
    val enabled by stream.enabled.collectAsStateWithLifecycle()
    val clients by stream.clients.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var on by rememberSaveable { mutableStateOf(enabled) }
    var pending by remember { mutableStateOf<PendingChange?>(null) }

    EditableSection(
        title = "Event stream",
        editing = editing,
        onEditingChange = { editing = it },
        dirty = on != enabled,
        onDiscard = { on = enabled },
        onSave = save@{
            if (on == enabled) {
                editing = false
                return@save null
            }
            val commit = {
                stream.setEnabled(on)
                editing = false
            }
            pending = if (on) {
                PendingChange(
                    title = "Turn on the event stream?",
                    text = "GET /api/v1/events starts answering. Any API key with the \"follow the event " +
                        "stream\" permission can then follow every incoming SMS and status update live.\n\nFrom now on, events are also kept in the " +
                        "database (as long as messages, at most ${EventStream.MAX_KEPT}) so clients can catch up " +
                        "after a dropped connection.",
                    confirmLabel = "Turn on",
                    commit = commit,
                )
            } else {
                PendingChange(
                    title = "Turn off the event stream?",
                    text = (if (clients > 0) "$clients connected client${if (clients == 1) " is" else "s are"} " +
                        "disconnected. " else "") +
                        "GET /api/v1/events answers 404 until you turn it on again, and new events are no " +
                        "longer kept for it.",
                    confirmLabel = "Turn off",
                    commit = commit,
                )
            }
            null
        },
        view = {
            Text(
                "Server-Sent Events at GET /api/v1/events: the same events webhooks get, pushed live to " +
                    "apps that keep a connection open, such as a client app on another phone. " +
                    "Needs an API key with the \"follow the event stream\" permission.",
                style = MaterialTheme.typography.bodySmall,
            )
            ValueRow("Serve the event stream", onOff(enabled))
            if (enabled) ValueRow("Connected clients", clients.toString())
        },
        edit = {
            SwitchRow(
                "Serve the event stream",
                on,
                { on = it },
                detail = "When off, the endpoint isn't served (404).",
            )
        },
    )

    PendingChangeDialog(pending) { pending = null }
}

@Composable
private fun StorageSettings(app: GatewayApp) {
    val context = LocalContext.current
    val settings = app.settings
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf(false) }
    var days by rememberSaveable { mutableStateOf(settings.messageRetentionDays.toString()) }
    var pending by remember { mutableStateOf<PendingChange?>(null) }
    var confirmCleanup by remember { mutableStateOf(false) }
    var sizeRefresh by remember { mutableIntStateOf(0) }
    val dbBytes = remember(sizeRefresh) {
        listOf("gateway.db", "gateway.db-wal", "gateway.db-shm").sumOf { context.getDatabasePath(it).length() }
    }

    fun retentionText(d: Int) = if (d == 0) "Forever" else "$d day${if (d == 1) "" else "s"}"

    EditableSection(
        title = "Message retention",
        editing = editing,
        onEditingChange = { editing = it },
        dirty = days != settings.messageRetentionDays.toString(),
        onDiscard = { days = settings.messageRetentionDays.toString() },
        onSave = save@{
            val newDays = days.toIntOrNull() ?: return@save "Enter a number of days, or 0 to keep forever."
            val old = settings.messageRetentionDays
            val commit = {
                settings.messageRetentionDays = newDays
                editing = false
            }
            if (newDays != 0 && (old == 0 || newDays < old)) {
                pending = PendingChange(
                    title = "Keep messages for ${retentionText(newDays).lowercase()}?",
                    text = "At the next daily cleanup, messages older than ${retentionText(newDays).lowercase()} " +
                        "are deleted for good.",
                    confirmLabel = "Save",
                    commit = commit,
                )
            } else {
                commit()
            }
            null
        },
        view = {
            Text(
                "Once a day, older messages are deleted and the database is compacted. " +
                    "Messages still being sent are always kept.",
                style = MaterialTheme.typography.bodySmall,
            )
            ValueRow("Keep messages for", retentionText(settings.messageRetentionDays))
        },
        edit = {
            OutlinedTextField(
                value = days,
                onValueChange = { days = digits(it) },
                label = { Text("Keep messages for (days, 0 = forever)") },
                supportingText = { Text("Default ${Settings.DEFAULT_RETENTION_DAYS}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
    )

    Section("Database") {
        ValueRow("Size", formatBytes(dbBytes))
        OutlinedButton(onClick = { confirmCleanup = true }) { Text("Clean up now") }
    }

    PendingChangeDialog(pending) { pending = null }
    if (confirmCleanup) {
        ConfirmDialog(
            title = "Clean up now?",
            text = if (settings.messageRetentionDays == 0) {
                "Nothing is deleted, since messages are kept forever. The database is compacted."
            } else {
                "Messages older than ${retentionText(settings.messageRetentionDays).lowercase()} are deleted " +
                    "for good, and the database is compacted."
            },
            confirmLabel = "Clean up",
            onDismiss = { confirmCleanup = false },
            onConfirm = {
                confirmCleanup = false
                CleanupWorker.runNow(context)
                // The job runs in the background; re-read the size once it has had time to finish.
                scope.launch {
                    delay(3_000)
                    sizeRefresh++
                }
            },
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

internal fun formatDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
