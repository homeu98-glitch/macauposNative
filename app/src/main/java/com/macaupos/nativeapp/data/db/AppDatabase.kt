package com.macaupos.nativeapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * App 資料庫 v1(P0 試金石:sync_outbox)。
 *
 * P1 會加:pos_orders、receipt_items、receipts、pos_shifts、pos_sessions、
 * pos_staff、pos_print_templates、merchants、pos_delivery_jobs 等(見計劃書 §5 P0 清單),
 * 每次加表 bump version + Migration。
 */
@Database(
    entities = [SyncOutboxEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun syncOutboxDao(): SyncOutboxDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "macaupos.db",
                ).build().also { instance = it }
            }
    }
}
