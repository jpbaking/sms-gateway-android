package io.github.jpbaking.smsgateway.tunnel

/** What a line of cloudflared's console log tells us. */
sealed interface LogEvent {
    data class Registered(val connIndex: Int) : LogEvent
    data class Dropped(val connIndex: Int?) : LogEvent
    data class QuickTunnelUrl(val url: String) : LogEvent
    data class Error(val message: String) : LogEvent
}

/**
 * Parses cloudflared's console log, e.g.
 * `2026-11-10T03:00:00Z INF Registered tunnel connection connIndex=0 connection=... protocol=quic`.
 * Kept free of Android types so it can be unit tested.
 */
object TunnelLog {
    private val connIndex = Regex("""\bconnIndex=(\d+)""")
    private val quickUrl = Regex("""https://[a-z0-9-]+\.trycloudflare\.com""")
    private val levelAndMessage = Regex("""^\S+\s+(INF|WRN|ERR|DBG|FTL)\s+(.*)$""")

    fun parse(line: String): LogEvent? {
        val index = connIndex.find(line)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            "Registered tunnel connection" in line && "Unregistered" !in line ->
                index?.let { LogEvent.Registered(it) }
            "Unregistered tunnel connection" in line || "Connection terminated" in line ||
                "Retrying connection in" in line -> LogEvent.Dropped(index)
            else -> {
                quickUrl.find(line)?.value?.takeIf { "api.trycloudflare.com" !in it && "login." !in it }
                    ?.let { return LogEvent.QuickTunnelUrl(it) }
                val m = levelAndMessage.find(line) ?: return null
                if (m.groupValues[1] == "ERR" || m.groupValues[1] == "FTL") LogEvent.Error(m.groupValues[2]) else null
            }
        }
    }
}

/** Waits between restarts after cloudflared exits on its own: 2s, 4s, 8s, ... up to a minute. */
fun restartDelayMillis(failures: Int): Long {
    val shift = (failures - 1).coerceIn(0, 5)
    return (2_000L shl shift).coerceAtMost(60_000L)
}
