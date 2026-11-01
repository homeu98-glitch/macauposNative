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
 * 線上訂單(Ledger)client —— 對齊 web `src/lib/ledger` 底下嘅模組:
 *
 * - `rpc list_merchant_orders(p_merchant_id, p_status, p_limit, p_since, p_since_id)`
 * - `rpc accept_order_in_store(p_order_id)`      ← 接單(店內)
 * - `rpc update_order_status(p_order_id, p_new_status)` ← 完成 / 製作中 / 可取
 * - `rpc set_order_paid_in_store(p_order_id)`    ← 標記已收款
 *
 * 鑑權:PostgREST `apikey` = Supabase publishable key;
 *       `Authorization: Bearer <session.ledgerAccessToken>`(過期用 refresh token 換新)。
 *
 * ⚠️ `/api/online-orders` 已 410 deprecated;`/api/admin/ledger/orders` 要 admin session,
 *    POS 終端用唔到 —— 所以行 RPC 呢條路(同 web 瀏覽器一樣)。
 */
class LedgerOrdersClient(private val deviceStore: DeviceStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()
    private val base = BuildConfig.SUPABASE_URL
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY

    data class OnlineOrder(
        val id: String,
        val status: String,
        val paymentStatus: String,
        val total: Double,
        val customerName: String?,
        val phone: String?,
        val fulfillmentType: String,
        val pickupCode: String?,
        val deliveryAddress: String?,
        val createdAt: String?,
        val itemSummary: String?,
        val raw: JSONObject,
    )

    sealed class Result {
        data class Ok(val orders: List<OnlineOrder>) : Result()
        data object Offline : Result()
        data class Fail(val error: String) : Result()
    }

    /** 待處理線上訂單(pending/accepted/preparing/ready 全拎落嚟,UI 再分組)。 */
    fun list(limit: Int = 50): Result {
        val merchantId = deviceStore.storeId
        if (merchantId.isBlank()) return Result.Fail("未登入(冇 merchantId)")
        val body = JSONObject()
            .put("p_merchant_id", merchantId)
            .put("p_status", JSONObject.NULL)
            .put("p_limit", limit)
            .put("p_since", JSONObject.NULL)
            .put("p_since_id", JSONObject.NULL)
        val res = rpc("list_merchant_orders", body) ?: return Result.Offline
        val (code, text) = res
        val arr = runCatching {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONArray()
        }.getOrElse { JSONArray() }
        if (code !in 200..299) return Result.Fail(errorOf(text, code))
        val list = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { map(it) }
        }
        return Result.Ok(list)
    }

    fun accept(orderId: String): kotlin.Result<Unit> =
        call("accept_order_in_store", JSONObject().put("p_order_id", orderId))

    fun updateStatus(orderId: String, newStatus: String): kotlin.Result<Unit> =
        call("update_order_status", JSONObject().put("p_order_id", orderId).put("p_new_status", newStatus))

    fun markPaid(orderId: String): kotlin.Result<Unit> =
        call("set_order_paid_in_store", JSONObject().put("p_order_id", orderId))

    /** 通用 RPC(會員查詢等共用同一套 token / refresh 邏輯)。 */
    fun rpcJson(fn: String, body: JSONObject): Pair<Int, String>? = rpc(fn, body)

    // ---- 內部 ----

    private fun call(fn: String, body: JSONObject): kotlin.Result<Unit> {
        val res = rpc(fn, body) ?: return kotlin.Result.failure(java.io.IOException("連唔到 server"))
        val (code, text) = res
        return if (code in 200..299) kotlin.Result.success(Unit)
        else kotlin.Result.failure(IllegalStateException(errorOf(text, code)))
    }

    /** @return (httpCode, bodyText) 或 null = 連唔到 */
    private fun rpc(fn: String, body: JSONObject): Pair<Int, String>? {
        val token = ensureToken() ?: return null
        val req = Request.Builder()
            .url("$base/rest/v1/rpc/$fn")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(json))
            .build()
        return try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code == 401) {
                    // token 過期 → refresh 一次再試
                    if (refreshToken()) return rpc(fn, body)
                }
                r.code to text
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun ensureToken(): String? = deviceStore.ledgerAccessToken.takeIf { it.isNotBlank() }

    /** Supabase refresh token 換新 access token(同 web `ensureLedgerSession()` 等價)。 */
    private fun refreshToken(): Boolean {
        val rt = deviceStore.ledgerRefreshToken
        if (rt.isBlank()) return false
        val body = JSONObject().put("refresh_token", rt)
        val req = Request.Builder()
            .url("$base/auth/v1/token?grant_type=refresh_token")
            .header("apikey", anonKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(json))
            .build()
        return try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code !in 200..299) return false
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return false
                val at = obj.optString("access_token", "")
                val newRt = obj.optString("refresh_token", rt)
                if (at.isBlank()) return false
                deviceStore.ledgerAccessToken = at
                deviceStore.ledgerRefreshToken = newRt
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun errorOf(text: String, code: Int): String =
        runCatching { JSONObject(text).optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "HTTP $code:${text.take(120)}"

    private fun map(o: JSONObject): OnlineOrder {
        val items = o.optJSONArray("items")
        val summary = items?.let { arr ->
            (0 until minOf(arr.length(), 4)).mapNotNull { i ->
                val it = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = it.optString("name", it.optString("item_name", ""))
                val qty = it.optInt("quantity", it.optInt("qty", 1))
                if (name.isBlank()) null else "$name×$qty"
            }.joinToString("、")
        }
        return OnlineOrder(
            id = o.optString("id"),
            status = o.optString("status", "pending"),
            paymentStatus = o.optString("payment_status", o.optString("paymentStatus", "unpaid")),
            total = o.optDouble("total", o.optDouble("total_amount", 0.0)),
            customerName = o.optString("customer_name", o.optString("customerName", "")).takeIf { it.isNotBlank() },
            phone = o.optString("phone", o.optString("customer_phone", "")).takeIf { it.isNotBlank() },
            fulfillmentType = o.optString("fulfillment_type", o.optString("fulfillmentType", "pickup")),
            pickupCode = o.optString("pickup_code", o.optString("pickupCode", "")).takeIf { it.isNotBlank() },
            deliveryAddress = o.optString("delivery_address", o.optString("deliveryAddress", "")).takeIf { it.isNotBlank() },
            createdAt = o.optString("created_at", o.optString("createdAt", "")).takeIf { it.isNotBlank() },
            itemSummary = summary?.takeIf { it.isNotBlank() },
            raw = o,
        )
    }
}
