package com.macaupos.nativeapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * App 資料庫 v2(P1-A:pos_orders + bootstrap_cache + sync_outbox)。
 *
 * 之後每次加表 bump version + Migration;P1 期內表多變動快,先用
 * fallbackToDestructiveMigration 開發(⚠️ 上線前要改返正式 Migration)。
 */
@Database(
    entities = [SyncOutboxEntity::class, PosOrderEntity::class, BootstrapCacheEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun posOrderDao(): PosOrderDao
    abstract fun bootstrapCacheDao(): BootstrapCacheDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "macaupos.db",
                )
                    // ⚠️ P1 開發期:schema 未定稿,升級就重嚟。上線前移除。
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
