package io.github.jpbaking.smsgateway.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.Serializable

@Serializable
data class SimInfo(
    val subscriptionId: Int,
    /** Zero-based physical slot (0 = SIM 1, 1 = SIM 2, ...). */
    val slot: Int,
    val displayName: String?,
    val carrierName: String?,
    /** Often empty: many carriers don't write the number to the SIM. */
    val number: String?,
    val isDefaultSms: Boolean,
)

/** Lists the active SIMs and picks the one a message should go out on. */
class SimRepository(private val context: Context) {

    private val subscriptionManager: SubscriptionManager =
        context.getSystemService(SubscriptionManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION", "MissingPermission")
    fun activeSims(): List<SimInfo> {
        if (!hasPermission()) return emptyList()
        val defaultSms = SubscriptionManager.getDefaultSmsSubscriptionId()
        val list = subscriptionManager.activeSubscriptionInfoList ?: return emptyList()
        return list.map {
            SimInfo(
                subscriptionId = it.subscriptionId,
                slot = it.simSlotIndex,
                displayName = it.displayName?.toString(),
                carrierName = it.carrierName?.toString(),
                number = it.number?.takeIf { n -> n.isNotBlank() },
                isDefaultSms = it.subscriptionId == defaultSms,
            )
        }.sortedBy { it.slot }
    }

    fun bySubscriptionId(subscriptionId: Int): SimInfo? =
        activeSims().firstOrNull { it.subscriptionId == subscriptionId }

    /**
     * Picks the SIM for an outgoing message.
     *
     * An explicit [subscriptionId] wins, then [slot]. With neither, the system's default SMS SIM
     * is used, and if there is none (the user picked "ask every time"), the lowest slot.
     * Returns null when a requested SIM isn't present, or when no SIM is active at all.
     */
    fun resolve(slot: Int?, subscriptionId: Int?): SimInfo? {
        val sims = activeSims()
        return when {
            subscriptionId != null -> sims.firstOrNull { it.subscriptionId == subscriptionId }
            slot != null -> sims.firstOrNull { it.slot == slot }
            else -> sims.firstOrNull { it.isDefaultSms } ?: sims.firstOrNull()
        }
    }
}
