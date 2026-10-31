package com.macaupos.nativeapp.data.repo

import android.content.Context
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.db.AppDatabase
import com.macaupos.nativeapp.data.db.PosOrderEntity
import com.macaupos.nativeapp.data.db.SyncOutboxEntity
import com.macaupos.nativeapp.domain.DailyOrderSeq
import com.macaupos.nativeapp.domain.OrderBuilder

/**
 * 建單 repository —— offline-first 閉環:
 *
 * 1. 當日單號(掃本地 Room,max+1,斷網連續)
 * 2. 建 `PosOrderEntity` → **即刻寫本地**(UI 即刻成功)
 * 3. outbox 入隊(ORDER_CREATED,dedupeKey=order:<id> 冪等)
 * 4. SyncFlusher 上網後推 `/api/pos/sync` → Supabase `pos_orders`
 */
class OrderRepository(context: Context, private val db: AppDatabase) {

    private val deviceStore = DeviceStore(context)

    suspend fun nextLocalOrderNo(prefix: String = OrderBuilder.PREFIX_DINE_IN): String {
        val todayKey = DailyOrderSeq.todayKey()
        val sinceMs = startOfToday()
        val orders = db.posOrderDao().ordersSince(deviceStore.storeId, sinceMs)
            .map { it.localOrderNo to it.createdAt }
        val seq = DailyOrderSeq.nextSeq(orders, prefix, todayKey)
        return DailyOrderSeq.format(prefix, seq)
    }

    suspend fun createOrder(built: OrderBuilder.Built): Result<PosOrderEntity> {
        return try {
            db.posOrderDao().insert(built.order)
            db.syncOutboxDao().enqueue(
                SyncOutboxEntity(
                    kind = "ORDER_CREATED",
                    dedupeKey = "order:${built.order.id}",
                    payload = built.payload.toString(),
                    createdAt = System.currentTimeMillis(),
                )
            )
            Result.success(built.order)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pendingCount(): Int = db.syncOutboxDao().pendingCount()

    private fun startOfToday(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
