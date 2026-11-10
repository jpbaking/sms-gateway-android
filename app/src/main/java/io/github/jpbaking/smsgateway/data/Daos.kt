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

    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE direction = 'OUT' AND status = 'QUEUED' ORDER BY createdAt")
    abstract suspend fun queued(): List<MessageEntity>

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

    @Query("DELETE FROM webhooks WHERE id = :id")
    suspend fun delete(id: String): Int
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

    @Query("DELETE FROM api_keys WHERE id = :id")
    suspend fun delete(id: String): Int
}
