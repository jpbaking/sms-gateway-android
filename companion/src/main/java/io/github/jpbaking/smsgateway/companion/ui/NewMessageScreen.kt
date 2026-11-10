package io.github.jpbaking.smsgateway.companion.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jpbaking.smsgateway.companion.CompanionApp
import io.github.jpbaking.smsgateway.companion.R
import io.github.jpbaking.smsgateway.companion.contacts.Contact
import io.github.jpbaking.smsgateway.companion.contacts.ContactMatch
import io.github.jpbaking.smsgateway.companion.sync.Conversations
import kotlinx.coroutines.delay

/** Pick who to text: a contact, a recent conversation, or a typed number. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(app: CompanionApp, back: () -> Unit, open: (number: String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val permitted by app.contacts.permitted.collectAsStateWithLifecycle()
    val messages by app.sync.messages.collectAsStateWithLifecycle()
    val known by app.contacts.known.collectAsStateWithLifecycle()
    var matches by remember { mutableStateOf<List<ContactMatch>>(emptyList()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        app.contacts.onPermissionMaybeChanged()
    }

    LaunchedEffect(query, permitted) {
        delay(150)
        matches = app.contacts.search(query)
    }

    val recent = remember(messages) {
        Conversations.group(messages, app.contacts::conversationKey).take(50)
    }
    val q = query.trim()
    val recentMatches = recent.filter { c ->
        q.isEmpty() || c.address.contains(q) || known[c.address]?.name?.contains(q, ignoreCase = true) == true
    }.take(if (q.isEmpty()) 10 else 5)
    val typedNumber = q.takeIf { it.isNotEmpty() && it.all { ch -> ch.isDigit() || ch in "+-() ." } && it.any(Char::isDigit) }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
                },
                title = { Text("New conversation") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("To") },
                placeholder = { Text("Name or number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { typedNumber?.let { open(it.filter { c -> c.isDigit() || c == '+' }) } }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            )
            if (!permitted) {
                Banner("Allow contacts to search them by name.", error = false, action = "Allow") {
                    launcher.launch(Manifest.permission.READ_CONTACTS)
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (typedNumber != null) {
                    item {
                        PickRow(
                            key = typedNumber,
                            contact = null,
                            title = "Send to ${app.contacts.format(typedNumber)}",
                            subtitle = null,
                        ) { open(typedNumber.filter { it.isDigit() || it == '+' }) }
                    }
                }
                if (recentMatches.isNotEmpty()) {
                    item { Section("Recent") }
                    items(recentMatches, key = { "r:" + it.key }) { c ->
                        val contact = known[c.address]
                        PickRow(c.key, contact, contact?.name ?: app.contacts.format(c.address), if (contact != null) app.contacts.format(c.address) else null) {
                            open(c.address)
                        }
                    }
                }
                if (matches.isNotEmpty()) {
                    item { Section("Contacts") }
                    items(matches, key = { "c:" + it.name + it.number }) { m ->
                        PickRow(
                            app.contacts.conversationKey(m.number),
                            Contact(m.name, m.photoUri),
                            m.name,
                            listOfNotNull(m.label, app.contacts.format(m.number)).joinToString(" · "),
                        ) { open(m.number) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun PickRow(key: String, contact: Contact?, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(key, contact, size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
