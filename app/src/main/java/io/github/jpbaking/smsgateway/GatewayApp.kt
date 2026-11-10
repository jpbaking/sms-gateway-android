package io.github.jpbaking.smsgateway

import android.app.Application
import io.github.jpbaking.smsgateway.data.ApiKeys
import io.github.jpbaking.smsgateway.data.AppDatabase
import io.github.jpbaking.smsgateway.data.Settings
import io.github.jpbaking.smsgateway.sms.SimRepository
import io.github.jpbaking.smsgateway.sms.SmsSender
import io.github.jpbaking.smsgateway.webhook.WebhookDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Holds the app-wide singletons. A DI framework would be overkill for this size of app.
 *
 * The transport-facing pieces (HTTP server today, a cloud relay client later) only talk to
 * [sender], [sims], [db] and [webhooks], so a second transport can be added next to the
 * HTTP server without touching the SMS code.
 */
class GatewayApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var db: AppDatabase
        private set
    lateinit var settings: Settings
        private set
    lateinit var apiKeys: ApiKeys
        private set
    lateinit var sims: SimRepository
        private set
    lateinit var webhooks: WebhookDispatcher
        private set
    lateinit var sender: SmsSender
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.create(this)
        settings = Settings(this)
        apiKeys = ApiKeys(db.apiKeys(), settings)
        scope.launch { apiKeys.migrateLegacyKey() }
        sims = SimRepository(this)
        webhooks = WebhookDispatcher(this, db.webhooks())
        sender = SmsSender(this, db.messages(), webhooks)
    }

    companion object {
        lateinit var instance: GatewayApp
            private set
    }
}
