package com.macaupos.nativeapp.data

import android.content.Context
import org.json.JSONObject

/**
 * 沽清 / 售罄狀態 —— 對齊 web `src/lib/pos/soldout.ts` + `pos-app.tsx`:
 *
 * 🔴 web 實況(2026-09-10 審查):`pos_soldout` 表**冇任何地方寫入**,POS 嘅沽清
 * 一直都係**本機 localStorage**(`pos-soldout-changed` 事件)+ `/api/inventory/soldout`
 * (TODO stub)。所以 native 對等做法 = 本機 JsonStore,UI 與落單判斷都讀同一份。
 *
 * 欄位:`soldOut`(bool)/ `remainingQty`(剩幾份,可空)/ `initialQty`(起始份數)
 */
class SoldOutStore(context: Context) {

    private val store = JsonStore(context, "soldout.json")

    data class State(
        val itemId: String,
        val soldOut: Boolean,
        val remainingQty: Int?,
        val initialQty: Int?,
        val updatedAt: Long,
    ) {
        /** 有剩餘數量時,顯示「只剩 N 份」 */
        val hasRemainingBadge: Boolean
            get() = !soldOut && remainingQty != null && remainingQty > 0 && (initialQty ?: 0) > 0
    }

    fun all(): Map<String, State> {
        val root = store.read() ?: return emptyMap()
        val items = root.optJSONObject("items") ?: return emptyMap()
        val out = LinkedHashMap<String, State>()
        items.keys().forEach { id ->
            val o = items.optJSONObject(id) ?: return@forEach
            out[id] = State(
                itemId = id,
                soldOut = o.optBoolean("soldOut", false),
                remainingQty = if (o.has("remainingQty") && !o.isNull("remainingQty")) o.optInt("remainingQty") else null,
                initialQty = if (o.has("initialQty") && !o.isNull("initialQty")) o.optInt("initialQty") else null,
                updatedAt = o.optLong("updatedAt", 0),
            )
        }
        return out
    }

    fun get(itemId: String): State? = all()[itemId]

    /** 設定沽清 / 恢復。 */
    fun setSoldOut(itemId: String, soldOut: Boolean) {
        val cur = get(itemId)
        put(
            State(
                itemId = itemId,
                soldOut = soldOut,
                remainingQty = if (soldOut) 0 else cur?.remainingQty,
                initialQty = cur?.initialQty,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** 設定剩餘份數(0 = 售罄;null = 清除限制)。 */
    fun setRemaining(itemId: String, remaining: Int?, initial: Int? = null) {
        val cur = get(itemId)
        put(
            State(
                itemId = itemId,
                soldOut = remaining != null && remaining <= 0,
                remainingQty = remaining,
                initialQty = initial ?: cur?.initialQty ?: remaining,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    fun clearItem(itemId: String) {
        val root = store.read() ?: JSONObject().put("items", JSONObject())
        val items = root.optJSONObject("items") ?: JSONObject()
        items.remove(itemId)
        root.put("items", items)
        store.write(root)
    }

    private fun put(state: State) {
        val root = store.read() ?: JSONObject().put("items", JSONObject())
        val items = root.optJSONObject("items") ?: JSONObject()
        items.put(
            state.itemId,
            JSONObject()
                .put("soldOut", state.soldOut)
                .put("remainingQty", state.remainingQty ?: JSONObject.NULL)
                .put("initialQty", state.initialQty ?: JSONObject.NULL)
                .put("updatedAt", state.updatedAt)
        )
        root.put("items", items)
        store.write(root)
    }
}
