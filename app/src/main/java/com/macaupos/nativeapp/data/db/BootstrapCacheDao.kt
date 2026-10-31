package com.macaupos.nativeapp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BootstrapCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: BootstrapCacheEntity)

    @Query("SELECT * FROM bootstrap_cache WHERE storeId = :storeId")
    suspend fun get(storeId: String): BootstrapCacheEntity?
}
