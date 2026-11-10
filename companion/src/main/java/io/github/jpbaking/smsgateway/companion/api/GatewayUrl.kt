package io.github.jpbaking.smsgateway.companion.api

object GatewayUrl {
    private val ipv4 = Regex("""^(\d{1,3}\.){3}\d{1,3}(:\d+)?(/.*)?$""")

    /**
     * Cleans up what was typed into the gateway URL box: trims it, drops a trailing `/` or
     * `/api/v1`, and adds a scheme when there is none (`http://` for an IP address, which is how
     * the gateway serves the LAN by default, `https://` for anything else, such as a tunnel host
     * name). Returns null when nothing usable is left.
     */
    fun normalize(input: String): String? {
        var url = input.trim()
        if (url.isEmpty()) return null
        if (!url.contains("://")) {
            url = (if (ipv4.matches(url) || url.startsWith("localhost")) "http://" else "https://") + url
        }
        url = url.trimEnd('/')
        if (url.endsWith("/api/v1")) url = url.removeSuffix("/api/v1").trimEnd('/')
        val scheme = url.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = url.substringAfter("://")
        if (rest.isEmpty() || rest.startsWith("/")) return null
        return "$scheme://$rest"
    }
}
