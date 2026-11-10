package io.github.jpbaking.smsgateway.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.jpbaking.smsgateway.GatewayApp
import io.github.jpbaking.smsgateway.R

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light system-bar icons always, since the app is always dark.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val app = application as GatewayApp
        setContent {
            GatewayTheme { GatewayRoot(app) }
        }
    }
}

private enum class Tab(val label: String, @DrawableRes val icon: Int) {
    STATUS("Status", R.drawable.ic_nav_status),
    MESSAGES("Messages", R.drawable.ic_nav_messages),
    SETTINGS("Settings", R.drawable.ic_nav_settings),
}

/**
 * Three tabs: Status (start/stop and where to reach the API), Messages, and Settings, whose areas
 * each open their own page. The bottom bar is hidden on a settings page, so a half-edited setting
 * can't be lost by switching tabs; Back leaves the page instead (asking first if there are unsaved
 * changes).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GatewayRoot(app: GatewayApp) {
    var tab by rememberSaveable { mutableStateOf(Tab.STATUS) }
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }

    // Composed before the pages, so a page's own Back handling (unsaved changes) wins over these.
    BackHandler(enabled = page != null) { page = null }
    BackHandler(enabled = page == null && tab != Tab.STATUS) { tab = Tab.STATUS }

    val current = page
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.title ?: if (tab == Tab.STATUS) "SMS Gateway" else tab.label) },
                navigationIcon = {
                    if (current != null) {
                        IconButton(onClick = { page = null }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (current == null) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(painterResource(t.icon), contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        when {
            current != null -> SettingsPageContent(app, current, padding)
            tab == Tab.STATUS -> StatusScreen(app, padding, openSettings = { tab = Tab.SETTINGS; page = it })
            tab == Tab.MESSAGES -> MessagesScreen(app, padding)
            else -> SettingsList(padding, open = { page = it })
        }
    }
}

/** A scrolling column of cards, padded for the app bars. */
@Composable
internal fun ScreenColumn(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
