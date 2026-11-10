package io.github.jpbaking.smsgateway.data

import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates, checks and revokes named API keys.
 *
 * A key's webhooks and event streams follow the key: revoking it deletes its webhooks, and
 * [onAccessReduced] is called whenever a key stops being good for what it could do a moment ago
 * (disabled, revoked, regenerated, or a scope taken away), so open event streams can be closed.
 */
class ApiKeys(
    private val dao: ApiKeyDao,
    private val settings: Settings,
    private val webhooks: WebhookDao,
    private val onAccessReduced: (keyId: String) -> Unit = {},
) {

    /** A key as it's shown once to the user, together with its stored record. */
    data class Issued(val record: ApiKeyEntity, val plaintext: String)

    // Remembers when each key's lastUsedAt was last written, so a busy client doesn't cause a
    // database write on every request.
    private val lastTouched = ConcurrentHashMap<String, Long>()

    fun observe(): Flow<List<ApiKeyEntity>> = dao.observeAll()

    /** Throws [IllegalArgumentException] if the name is blank, too long or already used. */
    suspend fun create(name: String, scopes: Set<Scope> = Scope.NEW_KEY): Issued {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Name is required" }
        require(clean.length <= 64) { "Name must be 64 characters or fewer" }
        require(dao.countByName(clean) == 0) { "A key named '$clean' already exists" }
        require(scopes.isNotEmpty()) { "Pick at least one permission" }
        val plaintext = newKey()
        val record = ApiKeyEntity(
            id = UUID.randomUUID().toString(),
            name = clean,
            keyHash = hash(plaintext),
            keyPrefix = prefixOf(plaintext),
            createdAt = System.currentTimeMillis(),
            scopes = Scope.format(scopes),
        )
        dao.insert(record)
        return Issued(record, plaintext)
    }

    /** Swaps in a new key under the same name. The old key stops working immediately. */
    suspend fun regenerate(id: String): Issued? {
        val plaintext = newKey()
        if (dao.replaceKey(id, hash(plaintext), prefixOf(plaintext)) == 0) return null
        lastTouched.remove(id)
        onAccessReduced(id)
        val record = dao.get(id) ?: return null
        return Issued(record, plaintext)
    }

    /** Deletes the key and the webhooks it registered. */
    suspend fun revoke(id: String): Boolean {
        // The key goes first, so it can't register another webhook while its webhooks are removed.
        val removed = dao.delete(id) > 0
        webhooks.deleteByKey(id)
        lastTouched.remove(id)
        onAccessReduced(id)
        return removed
    }

    /** A disabled key is refused and its webhooks are paused, until it's enabled again. */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled)
        if (!enabled) onAccessReduced(id)
    }

    /** Throws [IllegalArgumentException] if [scopes] is empty. Also marks the key as reviewed. */
    suspend fun setScopes(id: String, scopes: Set<Scope>) {
        require(scopes.isNotEmpty()) { "Pick at least one permission" }
        val before = dao.get(id)?.scopeSet() ?: return
        dao.setScopes(id, Scope.format(scopes))
        if (!scopes.containsAll(before)) onAccessReduced(id)
    }

    /** Returns the key's record if [provided] is a valid, enabled key, otherwise null. */
    suspend fun authenticate(provided: String): ApiKeyEntity? {
        val record = dao.byHash(hash(provided)) ?: return null
        if (!record.enabled) return null
        val now = System.currentTimeMillis()
        val previous = lastTouched[record.id] ?: record.lastUsedAt ?: 0L
        if (now - previous > TOUCH_INTERVAL_MS) {
            lastTouched[record.id] = now
            dao.touch(record.id, now)
        }
        return record
    }

    /**
     * Whether the key that passed [authenticate] as [authenticated] is still good for [scope]:
     * it still exists with the same value, is enabled and has the scope. For long-lived
     * connections, which outlive the check made when they were opened.
     */
    suspend fun stillAllows(authenticated: ApiKeyEntity, scope: Scope): Boolean {
        val current = dao.get(authenticated.id) ?: return false
        return current.enabled && current.keyHash == authenticated.keyHash && current.has(scope)
    }

    /** Whether a webhook registered by [keyId] may deliver: the key must still exist and be enabled. */
    suspend fun allowsDelivery(keyId: String): Boolean = dao.get(keyId)?.enabled == true

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
                    scopes = Scope.format(Scope.ALL),
                    needsReview = true,
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
