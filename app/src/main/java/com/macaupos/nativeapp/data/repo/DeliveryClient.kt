package com.macaupos.nativeapp.data.repo

import com.macaupos.nativeapp.BuildConfig
import com.macaupos.nativeapp.data.DeviceStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 外送派單 client —— 對齊 web `/api/pos/delivery/jobs`:
 *
 * - `GET ?storeId=&active=1&limit=50` → `{ok, jobs:[...], tableReady?:bool}`
 * - **零 polling**(J 2026-10-31 明確要求):只喺開頁 / 撳「手動刷新」先拉
 * - 狀態:`draft/queued/new/accepted/picked_up`(進行中)、`delivered/cancelled`(結束)
 * - 未跑 migration 0076 → `tableReady:false`(唔當錯誤)
 *
 * 狀態由外部 grabber / provider 寫入,web 亦冇前端改狀態嘅 route → **唯讀看板**。
 */
class DeliveryClient(private val deviceStore: DeviceStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Job(
        val id: String,
        val status: String,
        val orderNo: String,
        val platform: String?,
        val deliveryMode: String?,
        val provider: String?,
        val customerName: String?,
        val customerPhone: String?,
        val customerAddress: String?,
        val note: String?,
        val updatedAt: String?,
    )

    data class Board(val jobs: List<Job>, val tableReady: Boolean)

    sealed class Result {
        data class Ok(val board: Board) : Result()
        data object Offline : Result()
        data class Fail(val error: String) : Result()
    }

    fun board(): Result {
        val storeId = deviceStore.storeId
        if (storeId.isBlank()) return Result.Fail("未登入(冇 storeId)")
        val req = Request.Builder()
            .url("${BuildConfig.POS_URL}/api/pos/delivery/jobs?storeId=$storeId&active=1&limit=50")
            .apply {
                val token = deviceStore.posDeviceToken
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrNull()
                    ?: return Result.Fail("回應格式異常(HTTP ${resp.code})")
                if (!obj.optBoolean("ok", false)) {
                    return Result.Fail(obj.optString("error", "HTTP ${resp.code}"))
                }
                val tableReady = obj.optBoolean("tableReady", true)
                val arr = obj.optJSONArray("jobs") ?: JSONArray()
                val jobs = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { map(it) }
                }
                Result.Ok(Board(jobs, tableReady))
            }
        } catch (e: Exception) {
            Result.Offline
        }
    }

    private fun map(o: JSONObject): Job = Job(
        id = o.optString("id"),
        status = o.optString("status", "new"),
        orderNo = o.optString("order_no_snapshot", o.optString("order_ref", "")).ifBlank { "#" + o.optString("id").takeLast(6) },
        platform = o.optString("platform", "").takeIf { it.isNotBlank() },
        deliveryMode = o.optString("delivery_mode", "").takeIf { it.isNotBlank() },
        provider = o.optString("provider", "").takeIf { it.isNotBlank() },
        customerName = o.optString("customer_name", "").takeIf { it.isNotBlank() },
        customerPhone = o.optString("customer_phone", "").takeIf { it.isNotBlank() },
        customerAddress = o.optString("customer_address", "").takeIf { it.isNotBlank() },
        note = o.optString("note", "").takeIf { it.isNotBlank() },
        updatedAt = o.optString("updated_at", "").takeIf { it.isNotBlank() },
    )
}
