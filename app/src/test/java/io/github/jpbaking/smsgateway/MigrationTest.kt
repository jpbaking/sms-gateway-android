package io.github.jpbaking.smsgateway

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import io.github.jpbaking.smsgateway.data.AppDatabase
import io.github.jpbaking.smsgateway.data.Scope
import io.github.jpbaking.smsgateway.data.WebhookOrigin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Opens a database as an older build left it and checks that the migrations bring it to what
 * Room expects today. Room compares every table with the entities when it opens a migrated
 * database and throws if they differ, so a wrong migration fails here and not on someone's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var room: AppDatabase? = null

    @After
    fun close() {
        room?.close()
        context.deleteDatabase(NAME)
    }

    private fun open(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
            .also { room = it }

    /** The schema of version 4, exactly as Room created it, with one key and one webhook in it. */
    private fun createVersion4() {
        val file = context.getDatabasePath(NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE messages (id TEXT NOT NULL, direction TEXT NOT NULL, phoneNumber TEXT NOT NULL, " +
                    "body TEXT NOT NULL, subscriptionId INTEGER NOT NULL, simSlot INTEGER NOT NULL, status TEXT NOT NULL, " +
                    "partCount INTEGER NOT NULL, partsSent INTEGER NOT NULL, partsDelivered INTEGER NOT NULL, error TEXT, " +
                    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, apiKeyId TEXT, apiKeyName TEXT, PRIMARY KEY(id))",
            )
            db.execSQL("CREATE INDEX index_messages_direction_createdAt ON messages (direction, createdAt)")
            db.execSQL(
                "CREATE TABLE webhooks (id TEXT NOT NULL, url TEXT NOT NULL, events TEXT NOT NULL, secret TEXT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, headers TEXT, insecure INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(id))",
            )
            db.execSQL(
                "CREATE TABLE api_keys (id TEXT NOT NULL, name TEXT NOT NULL, keyHash TEXT NOT NULL, " +
                    "keyPrefix TEXT NOT NULL, createdAt INTEGER NOT NULL, lastUsedAt INTEGER, PRIMARY KEY(id))",
            )
            db.execSQL("CREATE UNIQUE INDEX index_api_keys_name ON api_keys (name)")
            db.execSQL("CREATE UNIQUE INDEX index_api_keys_keyHash ON api_keys (keyHash)")
            db.execSQL(
                "CREATE TABLE events (seq INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, event TEXT NOT NULL, " +
                    "payload TEXT NOT NULL, createdAt INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX index_events_createdAt ON events (createdAt)")

            db.execSQL("INSERT INTO api_keys (id, name, keyHash, keyPrefix, createdAt) VALUES ('k1', 'billing', 'hash', 'sgw_1a2b3c4d', 1)")
            db.execSQL(
                "INSERT INTO webhooks (id, url, events, secret, createdAt, insecure) " +
                    "VALUES ('w1', 'https://example.com/sms', 'sms:received', 'secret', 2, 0)",
            )
            db.execSQL(
                "INSERT INTO messages (id, direction, phoneNumber, body, subscriptionId, simSlot, status, partCount, " +
                    "partsSent, partsDelivered, createdAt, updatedAt) VALUES ('m1', 'IN', '+15550100', 'hi', 1, 0, 'RECEIVED', 1, 0, 0, 3, 3)",
            )
            db.version = 4
        }
    }

    @Test
    fun aVersion4DatabaseOpensAndKeepsWorking(): Unit = runBlocking {
        createVersion4()
        val db = open()

        // Keys from before scopes keep every scope, stay on, and are flagged for a look.
        val key = db.apiKeys().get("k1")
        assertNotNull(key)
        assertEquals(Scope.ALL, key!!.scopeSet())
        assertTrue(key.enabled)
        assertTrue(key.needsReview)
        assertNull(key.countries)
        assertEquals(0, key.maxParts)

        // Webhooks keep delivering; who added them isn't known.
        val hook = db.webhooks().get("w1")
        assertNotNull(hook)
        assertTrue(hook!!.enabled)
        assertEquals(WebhookOrigin.UNKNOWN, hook.createdVia)
        assertNull(hook.createdByKeyId)
        assertEquals(listOf("sms:received"), hook.eventNames())

        assertEquals("hi", db.messages().get("m1")?.body)
    }

    @Test
    fun aKeysWebhooksCanBeFoundAndRemovedWithIt(): Unit = runBlocking {
        createVersion4()
        val db = open()
        val dao = db.webhooks()
        val mine = dao.get("w1")!!.copy(id = "w2", createdByKeyId = "k1", createdByKeyName = "billing", createdVia = WebhookOrigin.API)
        dao.insert(mine)

        assertEquals(listOf("w2"), dao.byKey("k1").map { it.id })
        // Another key can't delete it, and the one nobody owns isn't anyone's to delete.
        assertEquals(0, dao.deleteOwned("w2", "other"))
        assertEquals(0, dao.deleteOwned("w1", "k1"))

        assertEquals(1, dao.setEnabled("w2", false))
        assertFalse(dao.get("w2")!!.enabled)

        assertEquals(1, dao.deleteByKey("k1"))
        assertEquals(listOf("w1"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun aFreshDatabaseStartsEmptyAtTheCurrentVersion(): Unit = runBlocking {
        val db = open()
        assertTrue(db.webhooks().all().isEmpty())
        assertNull(db.apiKeys().get("nope"))
        assertEquals(5, db.openHelper.readableDatabase.version)
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
