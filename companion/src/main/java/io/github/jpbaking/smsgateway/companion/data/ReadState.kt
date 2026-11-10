package io.github.jpbaking.smsgateway.companion.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which messages have been seen, per conversation: the time of the newest received message that
 * was on screen. Messages that were already on the gateway when the app first connected count as
 * read, so a fresh install doesn't show a wall of unread conversations.
 */
class ReadState(context: Context) {
    private val prefs = context.getSharedPreferences("read_state", Context.MODE_PRIVATE)

    private val _readUpTo = MutableStateFlow(
        prefs.all.mapNotNull { (k, v) -> if (k.startsWith(PREFIX) && v is Long) k.removePrefix(PREFIX) to v else null }.toMap(),
    )
    val readUpTo: StateFlow<Map<String, Long>> = _readUpTo.asStateFlow()

    private val _baseline = MutableStateFlow(prefs.getLong(KEY_BASELINE, 0L))

    /** Everything received up to this time is read. 0 until the first successful load. */
    val baseline: StateFlow<Long> = _baseline.asStateFlow()

    fun readUpTo(key: String): Long = maxOf(_baseline.value, _readUpTo.value[key] ?: 0L)

    fun markRead(key: String, upTo: Long) {
        if (upTo <= readUpTo(key)) return
        prefs.edit().putLong(PREFIX + key, upTo).apply()
        _readUpTo.update { it + (key to upTo) }
    }

    /** Called after the first successful load. */
    fun setBaselineIfUnset(upTo: Long) {
        if (_baseline.value != 0L) return
        val value = maxOf(upTo, 1L)
        prefs.edit().putLong(KEY_BASELINE, value).apply()
        _baseline.value = value
    }

    /** For a different gateway: start over. */
    fun reset() {
        prefs.edit().clear().apply()
        _readUpTo.value = emptyMap()
        _baseline.value = 0L
    }

    private companion object {
        const val PREFIX = "c:"
        const val KEY_BASELINE = "baseline"
    }
}
