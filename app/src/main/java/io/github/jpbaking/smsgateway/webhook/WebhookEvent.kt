package io.github.jpbaking.smsgateway.webhook

enum class WebhookEvent(val wireName: String) {
    RECEIVED("sms:received"),
    SENT("sms:sent"),
    DELIVERED("sms:delivered"),
    FAILED("sms:failed"),
    ;

    companion object {
        fun fromWireName(name: String): WebhookEvent? = entries.firstOrNull { it.wireName == name }
    }
}
