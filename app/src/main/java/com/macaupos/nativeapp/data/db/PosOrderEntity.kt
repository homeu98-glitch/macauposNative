package com.macaupos.nativeapp.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `pos_orders` 本地鏡像 —— 與 Supabase 同名同表。
 *
 * 欄位對齊 web `pos-order-row.ts` 嘅 `POS_ORDER_DB_COLUMNS`(P1-A 取核心子集;
 * reopen、platform、diff 等結帳期欄位 P1-B 結帳功能落地時補)。
 * 離線建單:先寫呢張表(status="sent_to_kitchen")+ outbox 入隊,
 * SyncFlusher 上網後 POST /api/pos/sync 推上 Supabase。
 */
@Entity(
    tableName = "pos_orders",
    indices = [
        Index(value = ["storeId", "localOrderNo"], unique = true),
        Index(value = ["storeId", "createdAt"]),
        Index(value = ["syncState"]),
    ],
)
data class PosOrderEntity(
    /** client 產生:`staff-<uuid8>`(web `newStaffOrderId()` 同款) */
    @PrimaryKey val id: String,
    val storeId: String,
    val localOrderNo: String,
    val tableId: String?,
    val tableName: String?,
    /** "sent_to_kitchen"(web `buildStaffOrder` 同款) */
    val status: String,
    /** "dine_in" | "takeaway"(web fulfillment_status;P1-A 兩種) */
    val fulfillmentStatus: String?,
    /** 已送廚房時間(epoch ms);未送 null */
    val sentToKitchenAt: Long?,
    /** JSON array:OrderItem[](menuItemId/name/quantity/price/printerGroup/note) */
    val items: String,
    val orderNote: String?,
    val subtotal: Double,
    val taxAmount: Double,
    val serviceChargeAmount: Double,
    val discountAmount: Double,
    val total: Double,
    /** "pos"(web `STAFF_POS_ORDER_SOURCE`) */
    val source: String,
    val paymentMethod: String?,
    val partySize: Int?,
    /** 結帳時間(epoch ms);未結帳 null(web settled_at —— 報表/交班日歸屬真源) */
    val settledAt: Long?,
    /** 實收(web receivedAmount);結帳時寫 */
    val receivedAmount: Double?,
    /** 找零(web changeDue) */
    val changeDue: Double?,
    /** 返結時間(web reopenedAt)—— 反結重開嘅審計欄位 */
    val reopenedAt: Long? = null,
    /** 首次結帳時間(web originalSettledAt)—— 重結唔可以覆蓋 */
    val originalSettledAt: Long? = null,
    /** 返結次數(web reopenCount) */
    val reopenCount: Int = 0,
    /** 返結原因(web reopenReason) */
    val reopenReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** client 端最後修改時間;server 對帳用(web 同名欄位) */
    val clientUpdatedAt: Long,
    /** 本地同步狀態:"pending" | "synced" | "failed" —— 對帳守護用 */
    val syncState: String = "pending",
)
