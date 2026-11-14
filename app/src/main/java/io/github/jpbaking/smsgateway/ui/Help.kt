package io.github.jpbaking.smsgateway.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import io.github.jpbaking.smsgateway.BuildConfig
import io.github.jpbaking.smsgateway.R

internal const val REPO_URL = "https://github.com/jpbaking/sms-gateway-android"

/**
 * A page of the user guide in `docs/`, e.g. `"tunnel.md"` or `"security.md#api-keys"`, on GitHub.
 * A release build links to its own tag, so the page describes the version that is installed.
 */
internal fun docsUrl(page: String): String = "$REPO_URL/blob/${BuildConfig.DOCS_REF}/docs/$page"

/** Opens [url] in the browser, or says so if there's no browser to open it in. */
internal fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No browser found to open $url", Toast.LENGTH_LONG).show()
    }
}

/** The "?" in the top bar: opens the [docs page][docsUrl] about what's on screen. */
@Composable
internal fun HelpButton(page: String) {
    val context = LocalContext.current
    IconButton(onClick = { openInBrowser(context, docsUrl(page)) }) {
        Icon(painterResource(R.drawable.ic_help), contentDescription = "Help")
    }
}
