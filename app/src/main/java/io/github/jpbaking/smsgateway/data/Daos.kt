package io.github.jpbaking.smsgateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MessageDao {
    @Insert
    abstract suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun get(id: String): MessageEntity?

    @Query(
        """
        SELECT * FROM messages
        WHERE (:direction IS NULL OR direction = :direction)
        ORDER BY createdAt DESC LIMIT :limit OFFSET :offset
        """,
    )
    abstract suspend fun list(direction: Direction?, limit: Int, offset: Int): List<MessageEntity>

    /** Outgoing messages sent with one API key, newest first. */
    @Query("SELECT * FROM messages WHERE apiKeyId = :apiKeyId ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    abstract suspend fun listSentBy(apiKeyId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE direction = 'OUT' AND status = 'QUEUED' ORDER BY createdAt")
    abstract suspend fun queued(): List<MessageEntity>

    /**
     * Deletes messages last updated before [cutoff]. Outgoing messages that are still queued or
     * being sent are kept, whatever their age.
     */
    @Query("DELETE FROM messages WHERE updatedAt < :cutoff AND status NOT IN ('QUEUED', 'SENDING')")
    abstract suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("UPDATE messages SET status = :status, error = :error, updatedAt = :now WHERE id = :id")
    abstract suspend fun setStatus(id: String, status: MessageStatus, error: String?, now: Long)

    @Query("UPDATE messages SET partCount = :parts, subscriptionId = :subId, simSlot = :slot, status = 'SENDING', updatedAt = :now WHERE id = :id")
    abstract suspend fun markSending(id: String, parts: Int, subId: Int, slot: Int, now: Long)

    @Query("UPDATE messages SET partsSent = partsSent + 1, updatedAt = :now WHERE id = :id")
    abstract suspend fun incrementSent(id: String, now: Long)

    @Query("UPDATE messages SET partsDelivered = partsDelivered + 1, updatedAt = :now WHERE id = :id")
    abstract suspend fun incrementDelivered(id: String, now: Long)

    /**
     * Records one part's sent callback and returns the updated row, so the caller can tell
     * whether this was the last part.
     */
    @Transaction
    open suspend fun recordPartSent(id: String, now: Long): MessageEntity? {
        incrementSent(id, now)
        return get(id)
    }

    @Transaction
    open suspend fun recordPartDelivered(id: String, now: Long): MessageEntity? {
        incrementDelivered(id, now)
        return get(id)
    }
}

@Dao
interface WebhookDao {
    @Insert
    suspend fun insert(webhook: WebhookEntity)

    @Query("SELECT * FROM webhooks WHERE id = :id")
    suspend fun get(id: String): WebhookEntity?

    @Query("SELECT * FROM webhooks ORDER BY createdAt")
    suspend fun all(): List<WebhookEntity>

    @Query("SELECT * FROM webhooks ORDER BY createdAt")
    fun observeAll(): Flow<List<WebhookEntity>>

    /** The webhooks one API key registered. */
    @Query("SELECT * FROM webhooks WHERE createdByKeyId = :keyId ORDER BY createdAt")
    suspend fun byKey(keyId: String): List<WebhookEntity>

    @Query("UPDATE webhooks SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean): Int

    @Query("DELETE FROM webhooks WHERE id = :id")
    suspend fun delete(id: String): Int

    /** Deletes a webhook only if [keyId] registered it. */
    @Query("DELETE FROM webhooks WHERE id = :id AND createdByKeyId = :keyId")
    suspend fun deleteOwned(id: String, keyId: String): Int

    @Query("DELETE FROM webhooks WHERE createdByKeyId = :keyId")
    suspend fun deleteByKey(keyId: String): Int
}

@Dao
interface ApiKeyDao {
    @Insert
    suspend fun insert(key: ApiKeyEntity)

    @Query("SELECT * FROM api_keys ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ApiKeyEntity>>

    @Query("SELECT * FROM api_keys WHERE keyHash = :hash")
    suspend fun byHash(hash: String): ApiKeyEntity?

    @Query("SELECT * FROM api_keys WHERE id = :id")
    suspend fun get(id: String): ApiKeyEntity?

    @Query("SELECT COUNT(*) FROM api_keys WHERE name = :name COLLATE NOCASE")
    suspend fun countByName(name: String): Int

    @Query("UPDATE api_keys SET keyHash = :hash, keyPrefix = :prefix, lastUsedAt = NULL WHERE id = :id")
    suspend fun replaceKey(id: String, hash: String, prefix: String): Int

    @Query("UPDATE api_keys SET lastUsedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("UPDATE api_keys SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean): Int

    /** Saving a key's scopes also counts as having reviewed it. */
    @Query("UPDATE api_keys SET scopes = :scopes, needsReview = 0 WHERE id = :id")
    suspend fun setScopes(id: String, scopes: String): Int

    @Query("DELETE FROM api_keys WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(event: EventEntity): Long

    /** Events after [seq], oldest first. */
    @Query("SELECT * FROM events WHERE seq > :seq ORDER BY seq LIMIT :limit")
    suspend fun after(seq: Long, limit: Int): List<EventEntity>

    @Query("SELECT MAX(seq) FROM events")
    suspend fun maxSeq(): Long?

    @Query("SELECT MIN(seq) FROM events")
    suspend fun minSeq(): Long?

    @Query("DELETE FROM events WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /** Keeps only the newest [keep] events. */
    @Query("DELETE FROM events WHERE seq <= (SELECT MAX(seq) FROM events) - :keep")
    suspend fun trimTo(keep: Int): Int
}
