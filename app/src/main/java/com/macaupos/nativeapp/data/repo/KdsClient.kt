package com.macaupos.nativeapp.data.repo

import com.macaupos.nativeapp.BuildConfig
import com.macaupos.nativeapp.data.DeviceStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * KDS(後廚屏)client —— 對齊 web `/api/pos/kds` 系列:
 *
 * - `GET  /api/pos/kds/board?storeId=&station=` →
 *   `{ok, orders:[{order:PosOrder,itemKeys:[...]}], states:[{order_id,item_key,done_qty}],
 *     stations:[...], degraded?}`
 * - `POST /api/pos/kds/items {storeId, orderId, itemKey, doneQty}` → 標記完成數量
 *
 * 🔴 `itemKey` 規則同 web `orderItemKey()` 一致:`menuItemId|specs|price|note`
 *    (specs 用 `groupId:optionId` 排序後 `|` 連接;native 暫時冇規格 → 空字串)
 * 🔴 `degraded=kds_state_table_missing` = `pos_kds_item_state` 未建,migration 0033 未跑
 */
class KdsClient(private val deviceStore: DeviceStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    data class KdsItem(
        val itemKey: String,
        val name: String,
        val quantity: Int,
        val doneQty: Int,
        val note: String?,
    )

    data class KdsOrder(
        val id: String,
        val orderNo: String,
        val tableName: String?,
        val status: String,
        val sentAt: String?,
        val items: List<KdsItem>,
    )

    data class Board(
        val orders: List<KdsOrder>,
        val degraded: Boolean,
        /** 可選工位:[(id, label, pending)] */
        val stations: List<Triple<String, String, Int>> = emptyList(),
    )

    sealed class Result {
        data class Ok(val board: Board) : Result()
        data object Offline : Result()
        data class Fail(val error: String) : Result()
    }

    fun board(station: String? = null): Result {
        val storeId = deviceStore.storeId
        if (storeId.isBlank()) return Result.Fail("未登入(冇 storeId)")
        val url = buildString {
            append("${BuildConfig.POS_URL}/api/pos/kds/board?storeId=$storeId")
            if (!station.isNullOrBlank()) append("&station=").append(station)
        }
        val req = Request.Builder()
            .url(url)
            .applyAuth()
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrNull()
                    ?: return Result.Fail("回應格式異常(HTTP ${resp.code})")
                if (!obj.optBoolean("ok", false)) return Result.Fail(obj.optString("error", "HTTP ${resp.code}"))

                // states: orderId|itemKey → doneQty
                val done = HashMap<String, Int>()
                obj.optJSONArray("states")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s = arr.optJSONObject(i) ?: continue
                        done["${s.optString("order_id")}\u0000${s.optString("item_key")}"] = s.optInt("done_qty", 0)
                    }
                }

                val ordersArr: JSONArray = obj.optJSONArray("orders") ?: JSONArray()
                val list = ArrayList<KdsOrder>(ordersArr.length())
                for (i in 0 until ordersArr.length()) {
                    val entry = ordersArr.optJSONObject(i) ?: continue
                    val order = entry.optJSONObject("order") ?: continue
                    val orderId = order.optString("id")
                    val itemsArr = order.optJSONArray("items") ?: JSONArray()
                    val keys = entry.optJSONArray("itemKeys")

                    val items = ArrayList<KdsItem>(itemsArr.length())
                    for (j in 0 until itemsArr.length()) {
                        val it = itemsArr.optJSONObject(j) ?: continue
                        val menuItemId = it.optString("menuItemId", it.optString("menu_item_id", ""))
                        val price = it.optDouble("price", 0.0)
                        val note = it.optString("note", "")
                        val key = if (keys != null && keys.length() == itemsArr.length()) keys.optString(j)
                        else "$menuItemId||$price|$note"
                        items.add(
                            KdsItem(
                                itemKey = key,
                                name = it.optString("name", "?"),
                                quantity = it.optInt("quantity", 1),
                                doneQty = done["$orderId\u0000$key"] ?: 0,
                                note = note.takeIf { n -> n.isNotBlank() },
                            )
                        )
                    }
                    list.add(
                        KdsOrder(
                            id = orderId,
                            orderNo = order.optString("local_order_no", order.optString("localOrderNo", "")),
                            tableName = order.optString("table_name", order.optString("tableName", ""))
                                .takeIf { t -> t.isNotBlank() },
                            status = order.optString("status", ""),
                            sentAt = order.optString("sent_to_kitchen_at", "").takeIf { s -> s.isNotBlank() },
                            items = items,
                        )
                    )
                }
                Result.Ok(
                    Board(
                        orders = list,
                        degraded = obj.optString("degraded", "") == "kds_state_table_missing",
                        stations = obj.optJSONArray("stations")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                                val id = s.optString("id", "")
                                if (id.isBlank()) null
                                else Triple(id, s.optString("label", s.optString("name", id)), s.optInt("pending", 0))
                            }
                        } ?: emptyList(),
                    )
                )
            }
        } catch (e: Exception) {
            Result.Offline
        }
    }

    fun setDone(orderId: String, itemKey: String, doneQty: Int): kotlin.Result<Unit> {
        val body = JSONObject()
            .put("storeId", deviceStore.storeId)
            .put("orderId", orderId)
            .put("itemKey", itemKey)
            .put("doneQty", doneQty)
        val req = Request.Builder()
            .url("${BuildConfig.POS_URL}/api/pos/kds/items")
            .applyAuth()
            .post(body.toString().toRequestBody(json))
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrNull()
                if (resp.code in 200..299 && obj?.optBoolean("ok", false) == true) kotlin.Result.success(Unit)
                else kotlin.Result.failure(IllegalStateException(obj?.optString("error") ?: "HTTP ${resp.code}"))
            }
        } catch (e: Exception) {
            kotlin.Result.failure(java.io.IOException("連唔到 server"))
        }
    }

    private fun Request.Builder.applyAuth(): Request.Builder {
        val token = deviceStore.posDeviceToken
        if (token.isNotBlank()) header("Authorization", "Bearer $token")
        return header("Content-Type", "application/json")
    }
}
