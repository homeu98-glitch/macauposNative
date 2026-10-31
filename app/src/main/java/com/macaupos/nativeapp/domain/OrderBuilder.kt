package com.macaupos.nativeapp.domain

import com.macaupos.nativeapp.data.db.PosOrderEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 建單 —— 移植 web `staff-order.ts` `buildStaffOrder`:
 *
 * - id = `staff-<uuid8>`、source = "pos"、status = "sent_to_kitchen"(落單即送廚房)
 * - items JSON = OrderItem[](menuItemId/name/quantity/price/printerGroup/note)
 * - 金額 = `OrderTotals.compute`(subtotal 不 round + tax + service)
 */
object OrderBuilder {

    const val SOURCE_POS = "pos"
    const val STATUS_SENT_TO_KITCHEN = "sent_to_kitchen"
    const val PREFIX_DINE_IN = "訂單"

    data class Item(
        val menuItemId: String,
        val name: String,
        val price: Double,
        val quantity: Int,
        val printerGroup: String?,
        val note: String?,
    )

    data class Built(
        val order: PosOrderEntity,
        /** 對應 Supabase pos_orders row(送 sync 用,欄位名同 DB) */
        val payload: JSONObject,
        /** items JSON(渲染廚房單用) */
        val itemsJson: JSONArray,
    )

    fun build(
        storeId: String,
        tableName: String,
        fulfillment: String, // "dine_in" | "takeaway"
        items: List<Item>,
        taxRate: Double?,
        serviceChargeRate: Double?,
        orderNote: String?,
        localOrderNo: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Built {
        require(items.isNotEmpty()) { "購物車係空嘅" }
        val id = "staff-" + UUID.randomUUID().toString().slice(0..7)
        val totals = OrderTotals.compute(
            items.map { OrderTotals.CartLine(it.price, it.quantity) },
            taxRate, serviceChargeRate,
        )
        val itemsJson = JSONArray()
        items.forEach {
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

        val order = PosOrderEntity(
            id = id,
            storeId = storeId,
            localOrderNo = localOrderNo,
            tableId = null,
            tableName = tableName.ifBlank { null },
            status = STATUS_SENT_TO_KITCHEN,
            fulfillmentStatus = fulfillment,
            sentToKitchenAt = nowMs,
            items = itemsJson.toString(),
            orderNote = orderNote?.takeIf { it.isNotBlank() },
            subtotal = totals.subtotal,
            taxAmount = totals.taxAmount,
            serviceChargeAmount = totals.serviceChargeAmount,
            discountAmount = 0.0,
            total = totals.total,
            source = SOURCE_POS,
            paymentMethod = null,
            partySize = null,
            settledAt = null,
            receivedAmount = null,
            changeDue = null,
            createdAt = nowMs,
            updatedAt = nowMs,
            clientUpdatedAt = nowMs,
            syncState = "pending",
        )

        // Supabase payload:欄位名同 DB 一致;時間轉 ISO 8601(Room 本地存 epoch)
        val payload = JSONObject()
            .put("id", order.id)
            .put("store_id", order.storeId)
            .put("local_order_no", order.localOrderNo)
            .put("table_name", order.tableName ?: JSONObject.NULL)
            .put("status", order.status)
            .put("fulfillment_status", order.fulfillmentStatus ?: JSONObject.NULL)
            .put("sent_to_kitchen_at", iso(order.sentToKitchenAt))
            .put("items", itemsJson)
            .put("order_note", order.orderNote ?: JSONObject.NULL)
            .put("subtotal", order.subtotal)
            .put("tax_amount", order.taxAmount)
            .put("service_charge_amount", order.serviceChargeAmount)
            .put("discount_amount", order.discountAmount)
            .put("total", order.total)
            .put("source", order.source)
            .put("created_at", iso(order.createdAt))
            .put("updated_at", iso(order.updatedAt))
            .put("client_updated_at", iso(order.clientUpdatedAt))

        return Built(order, payload, itemsJson)
    }

    /**
     * ISO 8601 UTC(例:`2026-11-01T02:30:00.000Z`)—— Postgres timestamptz 收呢個。
     * ⚠️ 唔可以用 `java.time.Instant`(API 26+);API 23 用 Calendar + SimpleDateFormat。
     */
    private fun iso(epochMs: Long?): Any {
        if (epochMs == null) return JSONObject.NULL
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = epochMs
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(cal.time)
    }

    /** 由 PrintJobDto 建廚房單渲染輸入(items JSON 同 order.items 同源)。 */
    fun printJobJson(order: PosOrderEntity, itemsJson: JSONArray): JSONObject =
        JSONObject()
            .put("id", order.id)
            .put("tableName", order.tableName ?: JSONObject.NULL)
            .put("ticketType", "kitchen")
            .put("items", itemsJson)
}
