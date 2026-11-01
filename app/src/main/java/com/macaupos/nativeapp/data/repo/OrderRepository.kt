package com.macaupos.nativeapp.data.repo

import android.content.Context
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.db.AppDatabase
import com.macaupos.nativeapp.data.db.PosOrderEntity
import com.macaupos.nativeapp.data.db.SyncOutboxEntity
import com.macaupos.nativeapp.domain.DailyOrderSeq
import com.macaupos.nativeapp.domain.OrderBuilder
import com.macaupos.nativeapp.domain.OrderTotals
import org.json.JSONArray
import org.json.JSONObject

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

    // ---- 結帳 / 加菜(P1-B)----

    /** 某枱最新一張**未結帳**單(status != settled,含 sent_to_kitchen/paid/reopened)。 */
    suspend fun openOrderForTable(tableName: String): PosOrderEntity? {
        val today = db.posOrderDao().ordersSince(deviceStore.storeId, 0)
        // 簡化 P1-B:掃該枱最近單,第一張未 settled 就係現有單
        return today.firstOrNull { it.tableName == tableName && it.status != "settled" }
    }

    suspend fun byId(id: String): PosOrderEntity? = db.posOrderDao().byId(id)

    /**
     * 結帳 —— 對齊 web `applyPaymentToOrder` + `confirmPayment`:
     * - status → "settled"、settled_at 寫入(報表/交班日歸屬真源)
     * - ORDER_SETTLED payload **補帶完整 items**(web 2026-09-17 退貨修復:
     *   結帳係最後一次有完整內容嘅時機,俾雲端一次自愈機會)
     */
    suspend fun settleOrder(
        order: PosOrderEntity,
        paymentMethod: String,
        receivedAmount: Double,
        /** 全單折扣(已計好金額;由 Discounts.apply 出) */
        discountAmount: Double = 0.0,
        /** 抹零金額(由 Discounts.apply 出,唔計入折扣) */
        rounding: Double = 0.0,
    ): Result<PosOrderEntity> = runCatching {
        val now = System.currentTimeMillis()
        val payable = com.macaupos.nativeapp.domain.ReportStats.round2(
            (order.total - discountAmount - rounding).coerceAtLeast(0.0)
        )
        val change = (receivedAmount - payable).coerceAtLeast(0.0)
        val settled = order.copy(
            status = "settled",
            paymentMethod = paymentMethod,
            total = payable,
            discountAmount = com.macaupos.nativeapp.domain.ReportStats.round2(
                order.discountAmount + discountAmount + rounding
            ),
            settledAt = now,
            receivedAmount = receivedAmount,
            changeDue = change,
            // 返結審計:重結唔可以覆蓋首次結帳時間(web `originalSettledAt ?? now`)
            originalSettledAt = order.originalSettledAt ?: now,
            updatedAt = now,
            clientUpdatedAt = now,
            syncState = "pending",
        )
        db.posOrderDao().upsert(settled)

        val payload = JSONObject()
            .put("orderId", settled.id)
            .put("total", settled.total)
            .put("receivedAmount", receivedAmount)
            .put("changeDue", change)
            .put("discountAmount", settled.discountAmount)
            .put("paymentMethod", paymentMethod)
            .put("memberPhone", JSONObject.NULL)
            .put("memberDeduction", 0)
            .put("couponDiscount", 0)
            .put("prepaidAmount", 0)
            .put("status", settled.status)
            .put("fulfillmentStatus", settled.fulfillmentStatus ?: JSONObject.NULL)
            .put("sentToKitchenAt", settled.sentToKitchenAt?.let { iso(it) } ?: JSONObject.NULL)
            .put("servedAt", JSONObject.NULL)
            .put("partySize", settled.partySize ?: JSONObject.NULL)
            .put("discountNote", JSONObject.NULL)
            .put("items", JSONArray(settled.items)) // 🔴 補帶完整內容(web 退貨修復)
        db.syncOutboxDao().enqueue(
            SyncOutboxEntity(
                kind = "ORDER_SETTLED",
                dedupeKey = "settle:${settled.id}:${now}",
                payload = payload.toString(),
                createdAt = now,
            )
        )
        settled
    }

    /**
     * 返結(反結重開) —— 對齊 web `status:"reopened"` 語意:
     * - status → "reopened",寫 reopenedAt / reopenReason / reopenCount+1
     * - **originalSettledAt 保留首次結帳時間**(重結都唔覆蓋)
     * - settledAt 清空(該單回到「未結帳」,可以再加菜/重結)
     * - 事件:ORDER_UPDATED `{order: row}`(web 冇 ORDER_REOPENED 類型,用 UPDATED)
     */
    suspend fun reopenOrder(order: PosOrderEntity, reason: String?): Result<PosOrderEntity> = runCatching {
        val now = System.currentTimeMillis()
        val reopened = order.copy(
            status = "reopened",
            settledAt = null,
            receivedAmount = null,
            changeDue = null,
            reopenedAt = now,
            reopenReason = reason?.takeIf { it.isNotBlank() },
            reopenCount = order.reopenCount + 1,
            originalSettledAt = order.originalSettledAt ?: order.settledAt,
            updatedAt = now,
            clientUpdatedAt = now,
            syncState = "pending",
        )
        db.posOrderDao().upsert(reopened)
        val payload = JSONObject().put("order", orderRowJson(reopened))
        db.syncOutboxDao().enqueue(
            SyncOutboxEntity(
                kind = "ORDER_UPDATED",
                dedupeKey = "reopen:${reopened.id}:${now}",
                payload = payload.toString(),
                createdAt = now,
            )
        )
        reopened
    }

    /**
     * 對帳用:將一張本機單重新排隊(標 pending + 補一條 ORDER_UPDATED)。
     * 用於「雲端查唔到本機單」嘅修復(web reconcile 同一招)。
     */
    suspend fun requeue(orderId: String): Result<Unit> = runCatching {
        val order = db.posOrderDao().byId(orderId) ?: throw IllegalStateException("本機冇呢張單")
        val now = System.currentTimeMillis()
        db.posOrderDao().setSyncState(orderId, "pending")
        db.syncOutboxDao().enqueue(
            SyncOutboxEntity(
                kind = "ORDER_UPDATED",
                dedupeKey = "requeue:${order.id}:${now}",
                payload = JSONObject().put("order", orderRowJson(order)).toString(),
                createdAt = now,
            )
        )
    }

    /**
     * 加菜 —— 對齊 web 加菜語意:
     * - 新 items 附加落 order.items;金額重計;**status 保留**(paid/reopened 唔可以倒退 ——
     *   web 3129 行起嗰段鐵律:加菜只係加內容,唔改變已收錢事實)
     * - ORDER_UPDATED payload = `{order: <row>}`(web sync-flush 同款)
     */
    suspend fun addItemsToOrder(
        existing: PosOrderEntity,
        newItems: List<OrderBuilder.Item>,
        taxRate: Double?,
        serviceChargeRate: Double?,
    ): Result<Pair<PosOrderEntity, JSONArray>> = runCatching {
        val now = System.currentTimeMillis()
        val itemsJson = JSONArray(existing.items)
        newItems.forEach {
            itemsJson.put(
                JSONObject()
                    .put("menuItemId", it.menuItemId)
                    .put("name", it.name)
                    .put("quantity", it.quantity)
                    .put("price", it.price)
                    .apply { it.printerGroup?.takeIf { g -> g.isNotBlank() }?.let { g -> put("printerGroup", g) } }
                    .apply { it.note?.takeIf { n -> n.isNotBlank() }?.let { n -> put("note", n) } }
            )
        }
        val all = (0 until itemsJson.length()).mapNotNull { itemsJson.optJSONObject(it) }
        val totals = OrderTotals.compute(
            all.map { OrderTotals.CartLine(it.optDouble("price", 0.0), it.optInt("quantity", 1)) },
            taxRate, serviceChargeRate,
        )
        val updated = existing.copy(
            items = itemsJson.toString(),
            subtotal = totals.subtotal,
            taxAmount = totals.taxAmount,
            serviceChargeAmount = totals.serviceChargeAmount,
            total = totals.total,
            updatedAt = now,
            clientUpdatedAt = now,
            syncState = "pending",
        )
        db.posOrderDao().upsert(updated)

        val row = orderRowJson(updated)
        db.syncOutboxDao().enqueue(
            SyncOutboxEntity(
                kind = "ORDER_UPDATED",
                dedupeKey = "orderupd:${updated.id}:${now}",
                payload = JSONObject().put("order", row).toString(),
                createdAt = now,
            )
        )
        updated to itemsJson
    }

    /** Room row → Supabase row JSON(同 OrderBuilder.payload 同構;結帳欄位齊)。 */
    fun orderRowJson(o: PosOrderEntity): JSONObject = JSONObject()
        .put("id", o.id)
        .put("store_id", o.storeId)
        .put("local_order_no", o.localOrderNo)
        .put("table_name", o.tableName ?: JSONObject.NULL)
        .put("status", o.status)
        .put("fulfillment_status", o.fulfillmentStatus ?: JSONObject.NULL)
        .put("sent_to_kitchen_at", iso(o.sentToKitchenAt))
        .put("items", JSONArray(o.items))
        .put("order_note", o.orderNote ?: JSONObject.NULL)
        .put("subtotal", o.subtotal)
        .put("tax_amount", o.taxAmount)
        .put("service_charge_amount", o.serviceChargeAmount)
        .put("discount_amount", o.discountAmount)
        .put("total", o.total)
        .put("source", o.source)
        .put("payment_method", o.paymentMethod ?: JSONObject.NULL)
        .put("party_size", o.partySize ?: JSONObject.NULL)
        .put("settled_at", iso(o.settledAt))
        .put("reopened_at", iso(o.reopenedAt))
        .put("original_settled_at", iso(o.originalSettledAt))
        .put("reopen_count", o.reopenCount)
        .put("reopen_reason", o.reopenReason ?: JSONObject.NULL)
        .put("created_at", iso(o.createdAt))
        .put("updated_at", iso(o.updatedAt))
        .put("client_updated_at", iso(o.clientUpdatedAt))

    private fun iso(epochMs: Long?): Any {
        if (epochMs == null) return JSONObject.NULL
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(epochMs))
    }

    private fun startOfToday(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
