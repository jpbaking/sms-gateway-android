package io.github.jpbaking.smsgateway.companion.ui

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
import io.github.jpbaking.smsgateway.companion.BuildConfig
import io.github.jpbaking.smsgateway.companion.R

/**
 * A page of the user guide in `docs/`, e.g. `"companion.md#set-up"`, on GitHub. A release build
 * links to its own tag, so the page describes the version that is installed.
 */
fun docsUrl(page: String): String =
    "https://github.com/jpbaking/sms-gateway-android/blob/${BuildConfig.DOCS_REF}/docs/$page"

/** Opens [docsUrl] for [page] in the browser, or says so if there's no browser to open it in. */
fun openHelp(context: Context, page: String) {
    val url = docsUrl(page)
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No browser found to open $url", Toast.LENGTH_LONG).show()
    }
}

/** The "?" in a top bar: opens the user guide at [page]. */
@Composable
fun HelpButton(page: String) {
    val context = LocalContext.current
    IconButton(onClick = { openHelp(context, page) }) {
        Icon(painterResource(R.drawable.ic_help), contentDescription = "Help")
    }
}
