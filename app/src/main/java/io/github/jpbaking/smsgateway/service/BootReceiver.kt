package io.github.jpbaking.smsgateway.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.jpbaking.smsgateway.GatewayApp

/** Restarts the server after a reboot or an app update, if auto-start is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as GatewayApp
        if (app.settings.autoStart) GatewayService.start(context)
    }
}
