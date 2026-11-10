package io.github.jpbaking.smsgateway.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.data.Direction
import io.github.jpbaking.smsgateway.data.MessageEntity
import java.text.DateFormat
import java.util.Date

private const val RECENT_LIMIT = 200

/** The latest messages, newest first, optionally only received or only sent ones. */
@Composable
internal fun MessagesScreen(app: GatewayApp, padding: PaddingValues) {
    val messages by remember { app.db.messages().observeRecent(RECENT_LIMIT) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var filter by rememberSaveable { mutableStateOf<Direction?>(null) }
    val shown = remember(messages, filter) { filter?.let { f -> messages.filter { it.direction == f } } ?: messages }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                FilterChip(selected = filter == Direction.IN, onClick = { filter = Direction.IN }, label = { Text("Received") })
                FilterChip(selected = filter == Direction.OUT, onClick = { filter = Direction.OUT }, label = { Text("Sent") })
            }
        }
        if (shown.isEmpty()) item { Text("Nothing yet.", modifier = Modifier.padding(top = 8.dp)) }
        items(shown, key = { it.id }) { MessageRow(it) }
        if (messages.size >= RECENT_LIMIT) {
            item {
                Text(
                    "Showing the latest $RECENT_LIMIT. Use GET /messages for older ones.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun MessageRow(m: MessageEntity) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val arrow = if (m.direction == Direction.IN) {
                "← from"
            } else {
                // Which API key sent it, e.g. "billing-app → to +63…".
                listOfNotNull(m.apiKeyName, "→ to").joinToString(" ")
            }
            val sim = if (m.simSlot >= 0) "SIM ${m.simSlot + 1}" else "SIM ?"
            Text(
                "$arrow ${m.phoneNumber} · $sim · ${m.status.name.lowercase()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(m.body, maxLines = 4)
            m.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(m.createdAt)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
