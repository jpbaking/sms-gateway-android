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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.ApiKeyEntity
import io.github.jpbaking.smsgateway.data.ApiKeys
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Lists named API keys by name only. A key's value is shown once, right after it's issued. */
@Composable
fun ApiKeysSection(app: GatewayApp) {
    val scope = rememberCoroutineScope()
    val keys by remember { app.apiKeys.observe() }.collectAsStateWithLifecycle(initialValue = emptyList())

    var showCreate by remember { mutableStateOf(false) }
    var issued by remember { mutableStateOf<ApiKeys.Issued?>(null) }
    var confirmRegenerate by remember { mutableStateOf<ApiKeyEntity?>(null) }
    var confirmRevoke by remember { mutableStateOf<ApiKeyEntity?>(null) }

    Section("API keys") {
        Text(
            "Each app or user gets its own key, so you can tell who sent what. Send it as the " +
                "X-API-Key header, or as Authorization: Bearer <key>.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (keys.isEmpty()) {
            Text("No keys yet. The API refuses every request until you create one.", color = MaterialTheme.colorScheme.error)
        }
        keys.forEach { key ->
            Column(Modifier.fillMaxWidth()) {
                Text(key.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${key.keyPrefix}…  ·  created ${formatTime(key.createdAt)}  ·  " +
                        (key.lastUsedAt?.let { "last used ${formatTime(it)}" } ?: "never used"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            onCreate = { name, onError ->
                scope.launch {
                    try {
                        issued = app.apiKeys.create(name)
                        showCreate = false
                    } catch (e: IllegalArgumentException) {
                        onError(e.message ?: "Invalid name")
                    }
                }
            },
        )
    }

    confirmRegenerate?.let { key ->
        ConfirmDialog(
            title = "Regenerate \"${key.name}\"?",
            text = "The current key stops working immediately. Anything using it needs the new key.",
            confirmLabel = "Regenerate",
            onDismiss = { confirmRegenerate = null },
            onConfirm = {
                confirmRegenerate = null
                scope.launch { issued = app.apiKeys.regenerate(key.id) }
            },
        )
    }

    confirmRevoke?.let { key ->
        ConfirmDialog(
            title = "Revoke \"${key.name}\"?",
            text = "The key stops working immediately and is deleted. Messages it already sent keep its name.",
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

@Composable
private fun CreateKeyDialog(onDismiss: () -> Unit, onCreate: (String, (String) -> Unit) -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New API key") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text("Name, e.g. billing-app") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { msg -> @Composable { Text(msg) } },
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) { error = it } }) { Text("Create") }
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
                Button(onClick = { copySensitive(context, issued.plaintext) }) { Text("Copy") }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Copies text and, on Android 13+, marks it sensitive so the clipboard preview hides it. */
private fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText("API key", text)
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

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
