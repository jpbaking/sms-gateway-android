package io.github.jpbaking.smsgateway.companion

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.jpbaking.smsgateway.companion.contacts.Contacts
import io.github.jpbaking.smsgateway.companion.data.ReadState
import io.github.jpbaking.smsgateway.companion.data.Settings
import io.github.jpbaking.smsgateway.companion.sync.GatewaySync
import kotlinx.coroutines.launch

class CompanionApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var readState: ReadState
        private set
    lateinit var contacts: Contacts
        private set
    lateinit var sync: GatewaySync
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        readState = ReadState(this)
        contacts = Contacts(this)
        sync = GatewaySync(settings.config, onFirstLoad = readState::setBaselineIfUnset)

        // The gateway connection lives only while the app is on screen (no background service).
        // ProcessLifecycleOwner waits a moment before reporting "stopped", so rotating the screen
        // or switching between this app's screens doesn't drop the connection.
        val process = ProcessLifecycleOwner.get()
        process.lifecycleScope.launch {
            process.repeatOnLifecycle(Lifecycle.State.STARTED) {
                contacts.onPermissionMaybeChanged()
                sync.runWhileVisible()
            }
        }
    }
}
