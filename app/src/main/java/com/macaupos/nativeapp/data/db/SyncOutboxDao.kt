package com.macaupos.nativeapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncOutboxDao {

    /** 入隊;dedupeKey 撞鍵 = 冇野心跳過(冪等)。回:實際新增嘅 row id,null = 已存在。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(entity: SyncOutboxEntity): Long?

    @Query("SELECT * FROM sync_outbox WHERE status = 'pending' ORDER BY createdAt ASC LIMIT :batch")
    suspend fun takePending(batch: Int): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE status = 'pending' ORDER BY createdAt ASC")
    fun observePending(): Flow<List<SyncOutboxEntity>>

    @Query("UPDATE sync_outbox SET status='inflight', attempts=attempts+1 WHERE id = :id")
    suspend fun markInflight(id: Long)

    @Query("UPDATE sync_outbox SET status='done', sentAt=:sentAt, lastError=NULL WHERE id = :id")
    suspend fun markDone(id: Long, sentAt: Long)

    @Query("UPDATE sync_outbox SET status='failed', lastError=:error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    @Query("UPDATE sync_outbox SET status='pending' WHERE status='inflight' AND attempts < :maxAttempts")
    suspend fun requeueStuckInflight(maxAttempts: Int)

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE status = 'pending'")
    suspend fun pendingCount(): Int

    @Query("DELETE FROM sync_outbox WHERE status='done' AND sentAt < :before")
    suspend fun pruneDone(before: Long)
}
