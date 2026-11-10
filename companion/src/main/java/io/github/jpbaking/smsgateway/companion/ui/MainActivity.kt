package io.github.jpbaking.smsgateway.companion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.jpbaking.smsgateway.companion.CompanionApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val app = application as CompanionApp
        setContent {
            CompanionTheme { CompanionRoot(app) }
        }
    }

    override fun onResume() {
        super.onResume()
        // The contacts permission can be changed in system settings while the app is away.
        (application as CompanionApp).contacts.onPermissionMaybeChanged()
    }
}

/** Where the app is. Each screen except the conversation list sits on top of it. */
sealed interface Screen {
    data object Conversations : Screen
    data class Thread(val key: String, val address: String) : Screen
    data object NewMessage : Screen
    data object Settings : Screen
}

private val ScreenSaver = Saver<Screen, List<String>>(
    save = {
        when (it) {
            Screen.Conversations -> arrayListOf("list")
            is Screen.Thread -> arrayListOf("thread", it.key, it.address)
            Screen.NewMessage -> arrayListOf("new")
            Screen.Settings -> arrayListOf("settings")
        }
    },
    restore = {
        when (it.firstOrNull()) {
            "thread" -> Screen.Thread(it[1], it[2])
            "new" -> Screen.NewMessage
            "settings" -> Screen.Settings
            else -> Screen.Conversations
        }
    },
)

@Composable
private fun CompanionRoot(app: CompanionApp) {
    val firstScreen = if (app.settings.config.value == null) Screen.Settings else Screen.Conversations
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf(firstScreen) }
    val home = { screen = Screen.Conversations }

    BackHandler(enabled = screen != Screen.Conversations) { home() }

    when (val s = screen) {
        Screen.Conversations -> ConversationListScreen(
            app,
            openThread = { key, address -> screen = Screen.Thread(key, address) },
            newMessage = { screen = Screen.NewMessage },
            openSettings = { screen = Screen.Settings },
        )
        is Screen.Thread -> ThreadScreen(app, s.key, s.address, back = home, openSettings = { screen = Screen.Settings })
        Screen.NewMessage -> NewMessageScreen(
            app,
            back = home,
            open = { number -> screen = Screen.Thread(app.contacts.conversationKey(number), number) },
        )
        Screen.Settings -> SettingsScreen(app, back = home)
    }
}
