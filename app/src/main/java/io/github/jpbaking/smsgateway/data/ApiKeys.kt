package io.github.jpbaking.smsgateway.data

import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Creates, checks and revokes named API keys. */
class ApiKeys(private val dao: ApiKeyDao, private val settings: Settings) {

    /** A key as it's shown once to the user, together with its stored record. */
    data class Issued(val record: ApiKeyEntity, val plaintext: String)

    // Remembers when each key's lastUsedAt was last written, so a busy client doesn't cause a
    // database write on every request.
    private val lastTouched = ConcurrentHashMap<String, Long>()

    fun observe(): Flow<List<ApiKeyEntity>> = dao.observeAll()

    /** Throws [IllegalArgumentException] if the name is blank, too long or already used. */
    suspend fun create(name: String): Issued {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Name is required" }
        require(clean.length <= 64) { "Name must be 64 characters or fewer" }
        require(dao.countByName(clean) == 0) { "A key named '$clean' already exists" }
        val plaintext = newKey()
        val record = ApiKeyEntity(
            id = UUID.randomUUID().toString(),
            name = clean,
            keyHash = hash(plaintext),
            keyPrefix = prefixOf(plaintext),
            createdAt = System.currentTimeMillis(),
        )
        dao.insert(record)
        return Issued(record, plaintext)
    }

    /** Swaps in a new key under the same name. The old key stops working immediately. */
    suspend fun regenerate(id: String): Issued? {
        val plaintext = newKey()
        if (dao.replaceKey(id, hash(plaintext), prefixOf(plaintext)) == 0) return null
        lastTouched.remove(id)
        val record = dao.get(id) ?: return null
        return Issued(record, plaintext)
    }

    suspend fun revoke(id: String): Boolean = dao.delete(id) > 0

    /** Returns the key's record if [provided] is a valid key, otherwise null. */
    suspend fun authenticate(provided: String): ApiKeyEntity? {
        val record = dao.byHash(hash(provided)) ?: return null
        val now = System.currentTimeMillis()
        val previous = lastTouched[record.id] ?: record.lastUsedAt ?: 0L
        if (now - previous > TOUCH_INTERVAL_MS) {
            lastTouched[record.id] = now
            dao.touch(record.id, now)
        }
        return record
    }

    /**
     * Builds before named keys had one unnamed key. Keep it working as a key named "default",
     * so existing integrations don't break, then forget the old copy.
     */
    suspend fun migrateLegacyKey() {
        val legacy = settings.legacyApiKey() ?: return
        if (dao.byHash(hash(legacy)) == null) {
            val name = if (dao.countByName(LEGACY_NAME) == 0) LEGACY_NAME else "$LEGACY_NAME-${legacy.take(4)}"
            dao.insert(
                ApiKeyEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    keyHash = hash(legacy),
                    keyPrefix = prefixOf(legacy),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        settings.clearLegacyApiKey()
    }

    companion object {
        private const val PREFIX = "sgw_"
        private const val LEGACY_NAME = "default"
        private const val TOUCH_INTERVAL_MS = 60_000L

        fun newKey(): String = PREFIX + Settings.randomToken(24)

        fun prefixOf(key: String): String = key.take(if (key.startsWith(PREFIX)) PREFIX.length + 8 else 8)

        /**
         * Plain SHA-256 is enough here: keys are 192 random bits, so there's nothing to
         * brute-force the way there would be with a human-chosen password.
         */
        fun hash(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
