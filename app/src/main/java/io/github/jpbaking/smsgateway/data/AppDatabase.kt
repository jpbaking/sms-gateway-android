package io.github.jpbaking.smsgateway.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, WebhookEntity::class, ApiKeyEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun webhooks(): WebhookDao
    abstract fun apiKeys(): ApiKeyDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "gateway.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v2: extra webhook headers, named API keys, and which key sent each message. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE webhooks ADD COLUMN headers TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN apiKeyId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN apiKeyName TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS api_keys (id TEXT NOT NULL, name TEXT NOT NULL, " +
                        "keyHash TEXT NOT NULL, keyPrefix TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                        "lastUsedAt INTEGER, PRIMARY KEY(id))",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_api_keys_name ON api_keys (name)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_api_keys_keyHash ON api_keys (keyHash)")
            }
        }
    }
}
