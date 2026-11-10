package io.github.jpbaking.smsgateway.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.jpbaking.smsgateway.R
import io.github.jpbaking.smsgateway.ui.MainActivity
import java.net.URI

/** Tells the phone's owner about things done through the API that they should know about. */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /**
     * A webhook is a new place for every incoming SMS to go, so adding one through the API is
     * never silent. Shows only the URL's host: the rest can carry tokens.
     */
    fun webhookAdded(keyName: String, url: String) {
        if (!canNotify()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Security", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val host = runCatching { URI(url).host }.getOrNull() ?: "a new address"
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "API key \"$keyName\" now forwards messages to $host. Not expected? Remove it under Settings > Webhooks."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.brand_green))
            .setContentTitle("Webhook added")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        // A different id each time, so one notification doesn't replace another.
        manager.notify(FIRST_ID + (System.currentTimeMillis() % 100_000).toInt(), notification)
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CHANNEL_ID = "security"
        const val FIRST_ID = 1_000
    }
}
