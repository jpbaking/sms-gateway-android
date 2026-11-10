package io.github.jpbaking.smsgateway.companion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jpbaking.smsgateway.companion.CompanionApp
import io.github.jpbaking.smsgateway.companion.R
import io.github.jpbaking.smsgateway.companion.api.Message
import io.github.jpbaking.smsgateway.companion.api.Sim
import io.github.jpbaking.smsgateway.companion.sync.Conversations
import io.github.jpbaking.smsgateway.companion.sync.LinkState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(app: CompanionApp, key: String, address: String, back: () -> Unit, openSettings: () -> Unit) {
    val all by app.sync.messages.collectAsStateWithLifecycle()
    val sims by app.sync.sims.collectAsStateWithLifecycle()
    val state by app.sync.state.collectAsStateWithLifecycle()
    val hasMore by app.sync.hasMore.collectAsStateWithLifecycle()
    val known by app.contacts.known.collectAsStateWithLifecycle()
    val generation by app.contacts.generation.collectAsStateWithLifecycle()

    val messages = remember(all, key) {
        all.filter { app.contacts.conversationKey(it.phoneNumber) == key }.sortedWith(Conversations.NEWEST_FIRST)
    }
    // Reply to the number as the newest message has it; for a new conversation, as entered.
    val replyTo = messages.firstOrNull()?.phoneNumber ?: address
    LaunchedEffect(replyTo, generation) { app.contacts.resolve(listOf(replyTo)) }
    val contact = known[replyTo]

    // Seen while open, so it no longer counts as unread.
    val newestIn = messages.firstOrNull { it.incoming }?.createdAt
    LaunchedEffect(key, newestIn) { if (newestIn != null) app.readState.markRead(key, newestIn) }

    // The SIM follows the conversation (where the newest message came in) until one is picked.
    var pickedSubscription by rememberSaveable(key) { mutableStateOf<Int?>(null) }
    val autoSim = remember(messages, sims) { Conversations.replySim(messages, sims) }
    val sim = sims.firstOrNull { it.subscriptionId == pickedSubscription } ?: autoSim

    var draft by rememberSaveable(key) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var loadingOlder by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    // Keep the newest message in view when one arrives or is sent.
    LaunchedEffect(messages.firstOrNull()?.id) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }

    fun send(body: String, using: Sim?, clearDraft: Boolean) {
        scope.launch {
            sending = true
            runCatching { app.sync.send(replyTo, body, using) }
                .onSuccess { if (clearDraft) draft = "" }
                .onFailure { snackbar.showSnackbar(it.message ?: "Couldn't send") }
            sending = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(key, contact, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(contact?.name ?: app.contacts.format(replyTo), maxLines = 1)
                            if (contact != null) {
                                Text(
                                    app.contacts.format(replyTo),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            LinkBanner(state, openSettings)
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (messages.isEmpty()) {
                    item { EmptyState("New conversation", "Your message goes out through the gateway.") }
                }
                itemsIndexed(messages, key = { _, m -> m.id }) { i, m ->
                    val older = messages.getOrNull(i + 1)
                    Column {
                        if (older == null || !isSameDay(older.createdAt, m.createdAt)) DaySeparator(m.createdAt)
                        Bubble(
                            m,
                            showSim = sims.size > 1,
                            sims = sims,
                            onRetry = { send(m.body, Conversations.match(m, sims) ?: sim, clearDraft = false) },
                        )
                    }
                }
                if (hasMore && messages.isNotEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            OutlinedButton(
                                enabled = !loadingOlder,
                                onClick = {
                                    scope.launch {
                                        loadingOlder = true
                                        runCatching { app.sync.loadOlder() }
                                            .onFailure { snackbar.showSnackbar(it.message ?: "Couldn't load") }
                                        loadingOlder = false
                                    }
                                },
                            ) { Text(if (loadingOlder) "Loading…" else "Load older messages") }
                        }
                    }
                }
            }
            Composer(
                draft = draft,
                onDraft = { draft = it },
                sim = sim,
                sims = sims,
                autoPicked = pickedSubscription == null,
                onPickSim = { pickedSubscription = it.subscriptionId },
                canSend = draft.isNotBlank() && !sending && state != LinkState.NotConfigured,
                onSend = { send(draft.trim(), sim, clearDraft = true) },
            )
        }
    }
}

@Composable
private fun DaySeparator(millis: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(formatDay(millis), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: Message, showSim: Boolean, sims: List<Sim>, onRetry: () -> Unit) {
    val context = LocalContext.current
    val failed = m.status == "failed"
    val bubbleColor = when {
        m.incoming -> MaterialTheme.colorScheme.surfaceContainerHigh
        failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val shape = if (m.incoming) {
        RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (m.incoming) Alignment.Start else Alignment.End,
    ) {
        Surface(
            color = bubbleColor,
            shape = shape,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = { if (failed) onRetry() },
                    onLongClick = { copy(context, m.body) },
                ),
        ) {
            Text(m.body, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyLarge)
        }
        val parts = buildList {
            add(formatTime(context, m.createdAt))
            if (showSim) {
                val s = Conversations.match(m, sims)
                add(s?.let { "SIM ${it.slot + 1}" } ?: m.simSlot?.let { "SIM ${it + 1}" } ?: "")
            }
            if (!m.incoming) add(statusText(m))
        }.filter { it.isNotEmpty() }
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun statusText(m: Message): String = when (m.status) {
    "queued" -> "Queued"
    "sending" -> "Sending…"
    "sent" -> "Sent"
    "delivered" -> "Delivered"
    "failed" -> "Not sent" + (m.error?.let { ": $it" } ?: "") + ". Tap to retry"
    else -> m.status
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Message", text))
    // Android 13+ shows its own confirmation.
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    sim: Sim?,
    sims: List<Sim>,
    autoPicked: Boolean,
    onPickSim: (Sim) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            if (sim != null) {
                var open by remember { mutableStateOf(false) }
                Box {
                    AssistChip(
                        onClick = { if (sims.size > 1) open = true },
                        label = { Text(if (sims.size > 1 && autoPicked) "${sim.label} (auto)" else sim.label) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_sim), contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        sims.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label + (s.number?.let { "\n$it" } ?: "")) },
                                onClick = { onPickSim(s); open = false },
                            )
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = draft,
                    onValueChange = onDraft,
                    placeholder = { Text("Text message") },
                    modifier = Modifier.weight(1f),
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = onSend, enabled = canSend) {
                    Icon(painterResource(R.drawable.ic_send), contentDescription = "Send")
                }
            }
        }
    }
}
