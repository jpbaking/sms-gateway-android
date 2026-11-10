package io.github.jpbaking.smsgateway.data

/**
 * What an API key may do. Stored on the key as comma separated [wire] names.
 *
 * A request for something the key lacks gets `403` with the missing scope's name.
 */
enum class Scope(val wire: String, val label: String, val detail: String) {
    SEND("send", "Send messages", "Send SMS, and read the status of the messages this key sent."),
    READ("read", "Read all messages", "Every message, including incoming ones such as one-time codes."),
    EVENTS("events", "Follow the event stream", "Every incoming SMS and status update, live."),
    WEBHOOKS("webhooks", "Manage its own webhooks", "Forward incoming SMS and status updates to a URL."),
    SEND_SHORTCODE("send:shortcode", "Send to short codes", "Numbers of 6 digits or fewer, which can be premium-rate."),
    ;

    companion object {
        /** What a new key gets unless told otherwise. */
        val NEW_KEY: Set<Scope> = setOf(SEND, READ)

        /** Everything: what keys made before scopes existed keep, so nothing breaks on upgrade. */
        val ALL: Set<Scope> = entries.toSet()

        fun fromWire(name: String): Scope? = entries.firstOrNull { it.wire == name }

        /** Unknown names are dropped, so a newer build's scopes don't break an older one. */
        fun parse(stored: String): Set<Scope> =
            stored.split(',').mapNotNull { fromWire(it.trim()) }.toSet()

        /** In declaration order, so the stored text doesn't depend on the order of selection. */
        fun format(scopes: Set<Scope>): String = entries.filter { it in scopes }.joinToString(",") { it.wire }
    }
}

/**
 * Tells short codes from ordinary phone numbers. Short codes can be premium-rate, so sending to
 * one needs [Scope.SEND_SHORTCODE].
 */
object ShortCodes {
    const val MAX_DIGITS = 6

    /** True for a number of [MAX_DIGITS] digits or fewer that isn't written in international form. */
    fun isShortCode(to: String): Boolean {
        val trimmed = to.trim()
        if (trimmed.startsWith("+")) return false
        val digits = trimmed.count(Char::isDigit)
        return digits in 1..MAX_DIGITS
    }
}
