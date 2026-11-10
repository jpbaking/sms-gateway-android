package io.github.jpbaking.smsgateway.companion.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jpbaking.smsgateway.companion.R
import io.github.jpbaking.smsgateway.companion.contacts.Contact
import io.github.jpbaking.smsgateway.companion.sync.LinkState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val avatarColors = listOf(
    Color(0xFF3B1B66), Color(0xFF0E5A2C), Color(0xFF0B4F57), Color(0xFF5A2E0E),
    Color(0xFF5A0E3B), Color(0xFF1B3B66), Color(0xFF4A4A0E), Color(0xFF2E0E5A),
)

/** A contact's photo, or their initial on a colour picked from [key], or a person icon. */
@Composable
fun Avatar(key: String, contact: Contact?, size: Dp = 44.dp) {
    val context = LocalContext.current
    val photo by produceState<ImageBitmap?>(null, contact?.photoUri) {
        value = contact?.photoUri?.let { loadPhoto(context, it) }
    }
    val bg = avatarColors[Math.floorMod(key.hashCode(), avatarColors.size)]
    Box(
        Modifier.size(size).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        val p = photo
        val initial = contact?.name?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
        when {
            p != null -> Image(p, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
            initial != null -> Text(initial.toString(), style = MaterialTheme.typography.titleMedium, color = Color.White)
            else -> Icon(painterResource(R.drawable.ic_person), contentDescription = null, tint = Color.White)
        }
    }
}

private suspend fun loadPhoto(context: Context, uri: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
    }.getOrNull()
}

private fun zoned(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

private fun pattern(skeleton: String) = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton))

fun formatTime(context: Context, millis: Long): String =
    DateFormat.getTimeFormat(context).format(java.util.Date(millis))

/** For the conversation list: the time today, the weekday this week, else the date. */
fun formatShort(context: Context, millis: Long): String {
    val then = zoned(millis)
    val today = LocalDate.now()
    val days = ChronoUnit.DAYS.between(then.toLocalDate(), today)
    return when {
        days <= 0L -> formatTime(context, millis)
        days < 7L -> then.format(pattern("EEE"))
        then.year == today.year -> then.format(pattern("MMMd"))
        else -> then.format(pattern("yMMMd"))
    }
}

/** For the day separators in a conversation. */
fun formatDay(millis: Long): String {
    val day = zoned(millis).toLocalDate()
    val today = LocalDate.now()
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.year == today.year -> zoned(millis).format(pattern("EEEEMMMMd"))
        else -> zoned(millis).format(pattern("yMMMMd"))
    }
}

fun isSameDay(a: Long, b: Long): Boolean = zoned(a).toLocalDate() == zoned(b).toLocalDate()

/** A strip under the top bar that says when the app isn't live. */
@Composable
fun LinkBanner(state: LinkState, openSettings: () -> Unit) {
    when (state) {
        LinkState.Live, LinkState.NotConfigured -> Unit
        LinkState.Connecting -> LinearProgressIndicator(Modifier.fillMaxWidth())
        LinkState.Polling -> Banner(
            "Live updates are off on the gateway, so this refreshes every 15 seconds. " +
                "Turn on Settings → Event stream in the gateway app.",
            error = false,
        )
        is LinkState.Offline -> Banner(
            text = when {
                state.authFailed -> "The gateway didn't accept the API key."
                state.certProblem -> "The gateway's certificate isn't trusted."
                else -> "Can't reach the gateway: ${state.message}. Retrying…"
            },
            error = true,
            action = if (state.authFailed || state.certProblem) "Settings" else null,
            onAction = openSettings,
        )
    }
}

@Composable
fun Banner(text: String, error: Boolean, action: String? = null, onAction: () -> Unit = {}) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainerCompat() else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

/** The palette doesn't set errorContainer, so make a dark one from the error colour. */
@Composable
private fun androidx.compose.material3.ColorScheme.errorContainerCompat(): Color = error.copy(alpha = 0.18f)

@Composable
fun EmptyState(title: String, body: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        if (body != null) {
            Spacer(Modifier.size(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
