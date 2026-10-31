package com.macaupos.nativeapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PosOrderDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(order: PosOrderEntity)

    @Query("SELECT * FROM pos_orders WHERE storeId = :storeId AND createdAt >= :sinceMs ORDER BY createdAt DESC")
    suspend fun ordersSince(storeId: String, sinceMs: Long): List<PosOrderEntity>

    @Query("SELECT * FROM pos_orders WHERE storeId = :storeId ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(storeId: String, limit: Int): Flow<List<PosOrderEntity>>

    @Query("SELECT * FROM pos_orders WHERE id = :id")
    suspend fun byId(id: String): PosOrderEntity?

    @Query("UPDATE pos_orders SET syncState=:state WHERE id = :id")
    suspend fun setSyncState(id: String, state: String)

    @Query("SELECT * FROM pos_orders WHERE syncState = 'pending' LIMIT :limit")
    suspend fun pendingSync(limit: Int): List<PosOrderEntity>

    @Query("SELECT COUNT(*) FROM pos_orders WHERE storeId = :storeId AND syncState = 'pending'")
    suspend fun pendingCount(storeId: String): Int
}
