package io.github.jpbaking.smsgateway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.ApiKeyEntity
import io.github.jpbaking.smsgateway.data.SecretBox
import io.github.jpbaking.smsgateway.data.Settings
import io.github.jpbaking.smsgateway.data.WebhookEntity
import io.github.jpbaking.smsgateway.data.WebhookOrigin
import io.github.jpbaking.smsgateway.webhook.WebhookEvent
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID

/**
 * Every webhook, whoever added it, with a switch and a Delete button each, and a form to add one
 * on the phone. Webhooks decide where incoming SMS go, so the phone's owner always has the last
 * word here, whatever the API did.
 */
@Composable
internal fun WebhooksSection(app: GatewayApp) {
    val scope = rememberCoroutineScope()
    val dao = app.db.webhooks()
    val hooks by remember { dao.observeAll() }.collectAsStateWithLifecycle(initialValue = null)
    val keys by remember { app.apiKeys.observe() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var phoneOnly by remember { mutableStateOf(app.settings.webhooksPhoneOnly) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<WebhookEntity?>(null) }
    // The signing secret of a webhook just added here, shown once.
    var newSecret by remember { mutableStateOf<String?>(null) }

    Section("Webhooks") {
        Text(
            "Each webhook receives incoming SMS and status updates at its URL. Switch one off to pause it, " +
                "or delete it.",
            style = MaterialTheme.typography.bodySmall,
        )
        val list = hooks
        when {
            list == null -> Text("Loading…")
            list.isEmpty() -> Text("None yet. Incoming SMS and delivery updates aren't sent anywhere.")
            else -> list.forEach { hook ->
                WebhookRow(
                    hook = hook,
                    key = keys.firstOrNull { it.id == hook.createdByKeyId },
                    onToggle = { on -> scope.launch { dao.setEnabled(hook.id, on) } },
                    onDelete = { confirmDelete = hook },
                )
                HorizontalDivider()
            }
        }
        Button(onClick = { showAdd = true }) { Text("Add webhook") }
    }

    Section("Who can change webhooks") {
        SwitchRow(
            "Manage webhooks on this phone only",
            phoneOnly,
            {
                phoneOnly = it
                app.settings.webhooksPhoneOnly = it
            },
            detail = if (phoneOnly) {
                "The API can't add or remove webhooks. A key can still list the ones it registered."
            } else {
                "API keys with the \"manage its own webhooks\" permission can add and remove their own."
            },
        )
    }

    if (showAdd) {
        AddWebhookDialog(
            onDismiss = { showAdd = false },
            onAdd = { url, events ->
                val secret = Settings.randomToken()
                scope.launch {
                    dao.insert(
                        WebhookEntity(
                            id = UUID.randomUUID().toString(),
                            url = url,
                            events = events.joinToString(","),
                            secret = SecretBox.encrypt(secret),
                            createdAt = System.currentTimeMillis(),
                            createdVia = WebhookOrigin.PHONE,
                        ),
                    )
                    showAdd = false
                    newSecret = secret
                }
            },
        )
    }

    confirmDelete?.let { hook ->
        ConfirmDialog(
            title = "Delete this webhook?",
            text = "${hook.url}\n\nNothing more is sent to it, and deliveries still waiting to be retried are dropped.",
            confirmLabel = "Delete",
            onDismiss = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                scope.launch { dao.delete(hook.id) }
            },
        )
    }

    newSecret?.let { secret ->
        val context = LocalContext.current
        AlertDialog(
            // Only the explicit button closes it, so the secret isn't lost to a stray tap.
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Signing secret") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Your server uses this to check that a delivery really came from this phone. " +
                            "Copy it now. It won't be shown again.",
                    )
                    SelectionContainer { Text(secret, fontFamily = FontFamily.Monospace) }
                    Button(onClick = { copySensitive(context, "Webhook secret", secret) }) { Text("Copy") }
                }
            },
            confirmButton = { TextButton(onClick = { newSecret = null }) { Text("Done") } },
        )
    }
}

/** [key] is the API key that registered the webhook, if it did and still exists. */
@Composable
private fun WebhookRow(hook: WebhookEntity, key: ApiKeyEntity?, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val keyOff = hook.createdByKeyId != null && key?.enabled != true
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hook.url,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = hook.enabled, onCheckedChange = onToggle)
        }
        Text(hook.eventNames().joinToString(", "), style = MaterialTheme.typography.bodySmall)
        val headers = hook.storedHeaders()
        Text(
            webhookOriginText(hook) + "  ·  ${formatDateTime(hook.createdAt)}" +
                (if (headers.isNotEmpty()) "  ·  headers: ${headers.joinToString(", ") { it.key }}" else ""),
            style = MaterialTheme.typography.bodySmall,
        )
        if (hook.insecure) {
            Text(
                "Certificate not checked: anyone on the network path could receive its deliveries.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when {
            !hook.enabled -> Text("Off: nothing is sent to it.", style = MaterialTheme.typography.bodySmall)
            keyOff -> Text(
                "Paused, because its API key is off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
    }
}

private fun webhookOriginText(hook: WebhookEntity): String = when {
    hook.createdByKeyName != null -> "Added by API key \"${hook.createdByKeyName}\""
    hook.createdVia == WebhookOrigin.PHONE -> "Added on this phone"
    else -> "Added through the API (key unknown)"
}

@Composable
private fun AddWebhookDialog(onDismiss: () -> Unit, onAdd: (url: String, events: List<String>) -> Unit) {
    var url by remember { mutableStateOf("") }
    var events by remember { mutableStateOf(WebhookEvent.entries.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add webhook") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim(); error = null },
                    label = { Text("URL") },
                    placeholder = { Text("https://example.com/sms") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { msg -> @Composable { Text(msg) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Text("Send it:", style = MaterialTheme.typography.titleSmall)
                WebhookEvent.entries.forEach { event ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = event in events,
                            onCheckedChange = { on -> events = if (on) events + event else events - event },
                        )
                        Text(event.wireName, fontFamily = FontFamily.Monospace)
                    }
                }
                Text(
                    "A signing secret is made for it and shown once after you add it. Extra headers can " +
                        "only be set through the API.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
                val host = runCatching { URI(url).host }.getOrNull()
                when {
                    (scheme != "http" && scheme != "https") || host.isNullOrBlank() ->
                        error = "Enter an http:// or https:// URL."
                    events.isEmpty() -> error = "Pick at least one event."
                    else -> onAdd(url, WebhookEvent.entries.filter { it in events }.map { it.wireName })
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
