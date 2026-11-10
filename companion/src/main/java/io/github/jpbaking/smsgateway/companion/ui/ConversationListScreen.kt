package io.github.jpbaking.smsgateway.companion.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jpbaking.smsgateway.companion.CompanionApp
import io.github.jpbaking.smsgateway.companion.R
import io.github.jpbaking.smsgateway.companion.sync.Conversation
import io.github.jpbaking.smsgateway.companion.sync.Conversations
import io.github.jpbaking.smsgateway.companion.sync.LinkState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    app: CompanionApp,
    openThread: (key: String, address: String) -> Unit,
    newMessage: () -> Unit,
    openSettings: () -> Unit,
) {
    val messages by app.sync.messages.collectAsStateWithLifecycle()
    val state by app.sync.state.collectAsStateWithLifecycle()
    val loaded by app.sync.loaded.collectAsStateWithLifecycle()
    val hasMore by app.sync.hasMore.collectAsStateWithLifecycle()
    val known by app.contacts.known.collectAsStateWithLifecycle()
    val permitted by app.contacts.permitted.collectAsStateWithLifecycle()
    val generation by app.contacts.generation.collectAsStateWithLifecycle()
    val readUpTo by app.readState.readUpTo.collectAsStateWithLifecycle()
    val baseline by app.readState.baseline.collectAsStateWithLifecycle()

    val conversations = remember(messages) { Conversations.group(messages, app.contacts::conversationKey) }
    LaunchedEffect(conversations, permitted, generation) {
        app.contacts.resolve(conversations.map { it.address })
    }

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refreshing by remember { mutableStateOf(false) }
    var loadingOlder by remember { mutableStateOf(false) }
    var askedContacts by rememberSaveable { mutableStateOf(false) }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        app.contacts.onPermissionMaybeChanged()
    }
    // Ask once, the first time the list is shown; a banner offers it again after a "no".
    LaunchedEffect(Unit) {
        if (!permitted && !askedContacts) {
            askedContacts = true
            contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages") },
                actions = {
                    IconButton(onClick = openSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            if (state != LinkState.NotConfigured) {
                ExtendedFloatingActionButton(
                    onClick = newMessage,
                    icon = { Icon(painterResource(R.drawable.ic_chat_add), contentDescription = null) },
                    text = { Text("Start chat") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            LinkBanner(state, openSettings)
            if (!permitted && state != LinkState.NotConfigured) {
                Banner(
                    "Allow contacts to see names instead of numbers.",
                    error = false,
                    action = "Allow",
                    onAction = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
                )
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        runCatching { app.sync.refresh() }
                            .onFailure { snackbar.showSnackbar(it.message ?: "Couldn't refresh") }
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state == LinkState.NotConfigured -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            EmptyState("Connect to your gateway", "Enter the gateway's URL and an API key.")
                            OutlinedButton(onClick = openSettings) { Text("Open settings") }
                        }
                    }
                    !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    conversations.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                        item { EmptyState("No messages yet", "Texts the gateway sends and receives show up here.") }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                        items(conversations, key = { it.key }) { c ->
                            val readTo = maxOf(baseline, readUpTo[c.key] ?: 0L)
                            ConversationRow(app, c, known[c.address], Conversations.unreadCount(c, readTo)) {
                                openThread(c.key, c.address)
                            }
                        }
                        if (hasMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
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
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    app: CompanionApp,
    c: Conversation,
    contact: io.github.jpbaking.smsgateway.companion.contacts.Contact?,
    unread: Int,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val latest = c.latest
    val bold = unread > 0
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.key, contact)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    contact?.name ?: app.contacts.format(c.address),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatShort(context, latest.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (bold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val prefix = when {
                    latest.incoming -> ""
                    latest.status == "failed" -> "Not sent: "
                    else -> "You: "
                }
                Text(
                    prefix + latest.body.replace('\n', ' '),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        latest.status == "failed" -> MaterialTheme.colorScheme.error
                        bold -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unread > 0) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Text(
                            if (unread > 99) "99+" else unread.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.size(0.dp))
                }
            }
        }
    }
}
