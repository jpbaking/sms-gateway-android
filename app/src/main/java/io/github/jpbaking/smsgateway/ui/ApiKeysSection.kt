package io.github.jpbaking.smsgateway.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.ApiKeyEntity
import io.github.jpbaking.smsgateway.data.ApiKeys
import io.github.jpbaking.smsgateway.data.Scope
import kotlinx.coroutines.launch

/**
 * Lists named API keys with what each may do. A key's value is shown once, right after it's
 * issued. Each key can be switched off without deleting it.
 */
@Composable
fun ApiKeysSection(app: GatewayApp) {
    val scope = rememberCoroutineScope()
    val keys by remember { app.apiKeys.observe() }.collectAsStateWithLifecycle(initialValue = emptyList())

    var showCreate by remember { mutableStateOf(false) }
    var issued by remember { mutableStateOf<ApiKeys.Issued?>(null) }
    var editScopes by remember { mutableStateOf<ApiKeyEntity?>(null) }
    var confirmRegenerate by remember { mutableStateOf<ApiKeyEntity?>(null) }
    var confirmRevoke by remember { mutableStateOf<ApiKeyEntity?>(null) }

    Section("API keys") {
        Text(
            "Each app or user gets its own key, with only the permissions it needs. Send it as the " +
                "X-API-Key header, or as Authorization: Bearer <key>.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (keys.isEmpty()) {
            Text("No keys yet. The API refuses every request until you create one.", color = MaterialTheme.colorScheme.error)
        }
        keys.forEach { key ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(key.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(
                        checked = key.enabled,
                        onCheckedChange = { on -> scope.launch { app.apiKeys.setEnabled(key.id, on) } },
                    )
                }
                Text(
                    "${key.keyPrefix}…  ·  created ${formatTime(key.createdAt)}  ·  " +
                        (key.lastUsedAt?.let { "last used ${formatTime(it)}" } ?: "never used"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "May: " + key.scopeSet().joinToString(", ") { it.label.lowercase() }.ifEmpty { "nothing" },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!key.enabled) {
                    Text(
                        "Off: its requests are refused and its webhooks are paused.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (key.needsReview) {
                    Text(
                        "Review this key: it was made before permissions existed, so it has all of them. " +
                            "Tap Permissions and keep only what it needs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { editScopes = key }) { Text("Permissions") }
                    TextButton(onClick = { confirmRegenerate = key }) { Text("Regenerate") }
                    TextButton(onClick = { confirmRevoke = key }) {
                        Text("Revoke", color = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider()
            }
        }
        Button(onClick = { showCreate = true }) { Text("New key") }
    }

    if (showCreate) {
        CreateKeyDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, scopes, onError ->
                scope.launch {
                    try {
                        issued = app.apiKeys.create(name, scopes)
                        showCreate = false
                    } catch (e: IllegalArgumentException) {
                        onError(e.message ?: "Invalid name")
                    }
                }
            },
        )
    }

    editScopes?.let { key ->
        ScopesDialog(
            key = key,
            onDismiss = { editScopes = null },
            onSave = { scopes ->
                editScopes = null
                scope.launch { runCatching { app.apiKeys.setScopes(key.id, scopes) } }
            },
        )
    }

    confirmRegenerate?.let { key ->
        ConfirmDialog(
            title = "Regenerate \"${key.name}\"?",
            text = "The current key stops working immediately, and any event stream it has open is closed. " +
                "Anything using it needs the new key. Its permissions and webhooks stay as they are.",
            confirmLabel = "Regenerate",
            onDismiss = { confirmRegenerate = null },
            onConfirm = {
                confirmRegenerate = null
                scope.launch { issued = app.apiKeys.regenerate(key.id) }
            },
        )
    }

    confirmRevoke?.let { key ->
        // The webhooks that go with the key, so nothing is deleted unseen.
        val hooks by produceState<List<String>?>(initialValue = null, key.id) {
            value = app.db.webhooks().byKey(key.id).map { it.url }
        }
        val hookText = hooks.orEmpty().let { urls ->
            when (urls.size) {
                0 -> ""
                1 -> "\n\nThe webhook it registered is deleted too:\n${urls.first()}"
                else -> "\n\nThe ${urls.size} webhooks it registered are deleted too:\n${urls.joinToString("\n")}"
            }
        }
        ConfirmDialog(
            title = "Revoke \"${key.name}\"?",
            text = "The key stops working immediately and is deleted, and any event stream it has open is " +
                "closed. Messages it already sent keep its name.$hookText",
            confirmLabel = "Revoke",
            onDismiss = { confirmRevoke = null },
            onConfirm = {
                confirmRevoke = null
                scope.launch { app.apiKeys.revoke(key.id) }
            },
        )
    }

    issued?.let { ShowKeyOnceDialog(it, onDone = { issued = null }) }
}

/** Checkboxes for what a key may do. Short codes only make sense for a key that can send. */
@Composable
private fun ScopePicker(selected: Set<Scope>, onChange: (Set<Scope>) -> Unit) {
    Column {
        Scope.entries.forEach { s ->
            val available = s != Scope.SEND_SHORTCODE || Scope.SEND in selected
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = s in selected,
                    enabled = available,
                    onCheckedChange = { on ->
                        val next = if (on) selected + s else selected - s
                        // Without "send", "send to short codes" has nothing to add to.
                        onChange(if (Scope.SEND in next) next else next - Scope.SEND_SHORTCODE)
                    },
                )
                Column {
                    Text(s.label)
                    Text(s.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CreateKeyDialog(onDismiss: () -> Unit, onCreate: (String, Set<Scope>, (String) -> Unit) -> Unit) {
    var name by remember { mutableStateOf("") }
    var scopes by remember { mutableStateOf(Scope.NEW_KEY) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New API key") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Name, e.g. billing-app") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { msg -> @Composable { Text(msg) } },
                )
                Text("This key may:", style = MaterialTheme.typography.titleSmall)
                ScopePicker(scopes) { scopes = it; error = null }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, scopes) { error = it } }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ScopesDialog(key: ApiKeyEntity, onDismiss: () -> Unit, onSave: (Set<Scope>) -> Unit) {
    var scopes by remember { mutableStateOf(key.scopeSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permissions for \"${key.name}\"") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScopePicker(scopes) { scopes = it }
                Text(
                    if (scopes.isEmpty()) {
                        "Pick at least one. To stop a key from doing anything, switch it off or revoke it."
                    } else {
                        "Taking a permission away applies at once. Without \"${Scope.EVENTS.label.lowercase()}\", " +
                            "an event stream the key has open is closed."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (scopes.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = scopes.isNotEmpty(), onClick = { onSave(scopes) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ShowKeyOnceDialog(issued: ApiKeys.Issued, onDone: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        // Only the explicit button closes it, so the key isn't lost to a stray tap.
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Key for \"${issued.record.name}\"") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Copy it now. It won't be shown again; if you lose it, regenerate it.")
                SelectionContainer {
                    Text(issued.plaintext, fontFamily = FontFamily.Monospace)
                }
                Button(onClick = { copySensitive(context, "API key", issued.plaintext) }) { Text("Copy") }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
    )
}

/** Copies text and, on Android 13+, marks it sensitive so the clipboard preview hides it. */
internal fun copySensitive(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        // Android 13+ shows its own "copied" confirmation.
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}

private fun formatTime(millis: Long): String = formatDateTime(millis)
