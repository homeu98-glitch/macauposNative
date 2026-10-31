package com.macaupos.nativeapp.data.repo

import com.macaupos.nativeapp.data.db.AppDatabase
import com.macaupos.nativeapp.data.db.BootstrapCacheEntity
import com.macaupos.nativeapp.supabase.Supabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 餐牌(bootstrap)資料源:
 * - 線上:Supabase `pos_bootstrap_config`(anon SELECT,RLS 放行 —— docs/92)
 * - 離線:Room `bootstrap_cache` 快取 → **斷網都開得了單**
 */
class BootstrapRepository(private val db: AppDatabase) {

    data class Bootstrap(
        val storeId: String,
        val storeName: String,
        val currency: String?,
        /** 原樣 payload JSONObject */
        val payload: JSONObject,
    ) {
        val rules: JSONObject get() = payload.optJSONObject("rules") ?: JSONObject()
        val taxRate: Double get() = rules.optDouble("taxRate", 0.0)
        val serviceChargeRate: Double get() = rules.optDouble("serviceChargeRate", 0.0)
        val menuItems: org.json.JSONArray get() = payload.optJSONArray("menu_items") ?: org.json.JSONArray()
        val categories: org.json.JSONArray get() = payload.optJSONArray("categories") ?: org.json.JSONArray()
        val tables: org.json.JSONArray get() = payload.optJSONArray("tables") ?: org.json.JSONArray()
    }

    /** 線上拉最新並快取;回 ok/error。冇設定 anon key 唔會爆,回錯誤訊息。 */
    suspend fun refresh(storeId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!Supabase.isConfigured()) {
            return@withContext Result.failure(IllegalStateException("SUPABASE_ANON_KEY 未設定(local.properties)"))
        }
        val res = Supabase.from("pos_bootstrap_config")
            .where("store_id=eq.$storeId")
            .limit(1)
            .select()
        if (res.offline) return@withContext Result.failure(java.io.IOException("連唔到 Supabase"))
        if (!res.ok) return@withContext Result.failure(IllegalStateException("Supabase HTTP ${res.code}:${res.body.take(200)}"))
        val arr = runCatching { res.jsonArray() }.getOrElse { return@withContext Result.failure(it) }
        if (arr.length() == 0) return@withContext Result.failure(IllegalStateException("呢間店仲未同步餐牌(pos_bootstrap_config 冇 row)"))
        val row = arr.getJSONObject(0)
        val payload = JSONObject()
            .put("storeId", row.optString("store_id"))
            .put("storeName", row.optString("store_name"))
            .put("currency", row.optString("currency"))
            .put("categories", row.opt("categories") ?: org.json.JSONArray())
            .put("menu_items", row.opt("menu_items") ?: org.json.JSONArray())
            .put("tables", row.opt("tables") ?: org.json.JSONArray())
            .put("rules", row.opt("rules") ?: org.json.JSONObject())
            .put("printer_groups", row.opt("printer_groups") ?: org.json.JSONArray())
        db.bootstrapCacheDao().put(
            BootstrapCacheEntity(
                storeId = storeId,
                storeName = row.optString("store_name"),
                currency = row.optString("currency").takeIf { it.isNotBlank() },
                payload = payload.toString(),
                sourceVersion = row.optLong("source_version", 1),
                fetchedAt = System.currentTimeMillis(),
            )
        )
        Result.success(Unit)
    }

    /** 離線優先讀取:淨係讀快取(斷網照用)。 */
    suspend fun cached(storeId: String): Bootstrap? = withContext(Dispatchers.IO) {
        val e = db.bootstrapCacheDao().get(storeId) ?: return@withContext null
        runCatching {
            Bootstrap(e.storeId, e.storeName, e.currency, JSONObject(e.payload))
        }.getOrNull()
    }
}
