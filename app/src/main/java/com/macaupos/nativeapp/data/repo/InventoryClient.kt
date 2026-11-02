package com.macaupos.nativeapp.data.repo

import com.macaupos.nativeapp.BuildConfig
import com.macaupos.nativeapp.data.DeviceStore
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 庫存 client —— 對齊 web `/api/inventory/products?store=<merchantId>`:
 * 回 `{ok, products:[InvProduct]}`;欄位 id/name/category/unit/current_qty/
 * avg_unit_cost/reorder_level/is_active。
 *
 * ⚠️ 呢個 module 只做**總覽**(唯讀):新增品項／入貨／盤點要用 web 庫存頁
 * (涉及收據綁定與成本計算,唔適合喺 POS 端另起一套)。
 */
class InventoryClient(private val deviceStore: DeviceStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class Product(
        val id: String,
        val name: String,
        val category: String?,
        val unit: String,
        val currentQty: Double,
        val avgUnitCost: Double,
        val reorderLevel: Double,
        val active: Boolean,
    ) {
        val lowStock: Boolean get() = reorderLevel > 0 && currentQty <= reorderLevel
    }

    sealed class Result {
        data class Ok(val products: List<Product>) : Result()
        data object Offline : Result()
        data class Fail(val error: String) : Result()
    }

    fun list(): Result {
        val storeId = deviceStore.storeId
        if (storeId.isBlank()) return Result.Fail("未登入(冇 storeId)")
        val req = Request.Builder()
            .url("${BuildConfig.POS_URL}/api/inventory/products?store=$storeId")
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrNull()
                    ?: return Result.Fail("回應格式異常(HTTP ${resp.code})")
                if (!obj.optBoolean("ok", false)) return Result.Fail(obj.optString("error", "HTTP ${resp.code}"))
                val arr: JSONArray = obj.optJSONArray("products") ?: JSONArray()
                val list = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let {
                        Product(
                            id = it.optString("id"),
                            name = it.optString("name", "?"),
                            category = it.optString("category", "").takeIf { c -> c.isNotBlank() },
                            unit = it.optString("unit", "件"),
                            currentQty = it.optDouble("current_qty", 0.0),
                            avgUnitCost = it.optDouble("avg_unit_cost", 0.0),
                            reorderLevel = it.optDouble("reorder_level", 0.0),
                            active = it.optBoolean("is_active", true),
                        )
                    }
                }
                Result.Ok(list)
            }
        } catch (e: Exception) {
            Result.Offline
        }
    }

    /**
     * 盤點:設定新庫存量 —— 對齊 web `POST /api/inventory/products/[id]/adjust?store=`:
     * body `{new_qty, reason}`(server 會寫異動紀錄)。
     */
    fun adjust(productId: String, newQty: Double, reason: String?): kotlin.Result<Unit> {
        val storeId = deviceStore.storeId
        if (storeId.isBlank()) return kotlin.Result.failure(IllegalStateException("未登入"))
        val body = JSONObject().put("new_qty", newQty).put("reason", reason ?: "POS 盤點")
        val req = Request.Builder()
            .url("${BuildConfig.POS_URL}/api/inventory/products/$productId/adjust?store=$storeId")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrNull()
                if (resp.code in 200..299 && obj?.optBoolean("ok", false) != false) kotlin.Result.success(Unit)
                else kotlin.Result.failure(IllegalStateException(obj?.optString("error") ?: "HTTP ${resp.code}"))
            }
        } catch (e: Exception) {
            kotlin.Result.failure(java.io.IOException("連唔到 server"))
        }
    }
}
