package com.iromashka.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MessageEntity::class, ContactEntity::class, GroupMessageEntity::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun groupMessageDao(): GroupMessageDao
    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_5 = object : Migration(1, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS messages_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, chatUin INTEGER NOT NULL, senderUin INTEGER NOT NULL, receiverUin INTEGER NOT NULL, text TEXT NOT NULL, timestamp INTEGER NOT NULL, isOutgoing INTEGER NOT NULL, isE2E INTEGER NOT NULL DEFAULT 1, isRead INTEGER NOT NULL DEFAULT 0, isEdited INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("INSERT OR IGNORE INTO messages_new (id, chatUin, senderUin, receiverUin, text, timestamp, isOutgoing, isE2E) SELECT id, chatUin, senderUin, receiverUin, text, timestamp, isOutgoing, isE2E FROM messages")
                db.execSQL("DROP TABLE messages")
                db.execSQL("ALTER TABLE messages_new RENAME TO messages")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chatUin ON messages(chatUin)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_timestamp ON messages(timestamp)")
            }
        }

        private val MIGRATION_2_5 = object : Migration(2, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN isRead INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN isEdited INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_5 = object : Migration(3, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN isRead INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN isEdited INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN isRead INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN isEdited INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "icq20.db"
                )
                .addMigrations(MIGRATION_1_5, MIGRATION_2_5, MIGRATION_3_5, MIGRATION_4_5)
                .build()
                .also { instance = it }
            }

        fun clearInstance() {
            instance = null
        }
    }
}
