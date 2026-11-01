package com.macaupos.nativeapp.data.repo

import android.content.Context
import android.os.Build
import com.macaupos.nativeapp.BuildConfig
import com.macaupos.nativeapp.data.db.AppDatabase
import com.macaupos.nativeapp.data.db.BootstrapCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 餐牌(bootstrap)資料源(2026-11-01 修正):
 *
 * 🔴 唔可以直打 Supabase 表 —— 實測發現 web 嘅 server route 用 server-only env
 * (`SUPABASE_URL`),同瀏覽器用嘅 `NEXT_PUBLIC_SUPABASE_URL`(zymdem)可以係唔同
 * project;`pos_orders`/`pos_bootstrap_config` 喺 zymdem 上根本唔存在(404 PGRST205)。
 * 所以 native 資料面要同 web 瀏覽器完全一樣:經 Vercel `/api/pos/bootstrap`。
 *
 * - 線上:`GET {POS_URL}/api/pos/bootstrap?storeId=...`(免鑑權,120/min/ip;
 *   server 內部讀 pos_bootstrap_config;未知店回 menuUnavailable=true)
 * - 離線:Room `bootstrap_cache` 快取 → **斷網都開得了單**
 */
class BootstrapRepository(private val appContext: Context, private val db: AppDatabase) {

    data class Bootstrap(
        val storeId: String,
        val storeName: String,
        val currency: String?,
        /** 原樣 payload JSONObject(Vercel normalize 後嘅 camelCase 結構) */
        val payload: JSONObject,
    ) {
        val rules: JSONObject get() = payload.optJSONObject("rules") ?: JSONObject()
        val taxRate: Double get() = rules.optDouble("taxRate", 0.0)
        val serviceChargeRate: Double get() = rules.optDouble("serviceChargeRate", 0.0)
        val menuItems: org.json.JSONArray
            get() = payload.optJSONArray("menuItems") ?: payload.optJSONArray("menu_items") ?: org.json.JSONArray()
        val categories: org.json.JSONArray
            get() = payload.optJSONArray("categories") ?: org.json.JSONArray()
        val tables: org.json.JSONArray
            get() = payload.optJSONArray("tables") ?: org.json.JSONArray()
        /** server 明確話「呢間店未開放餐牌」(未知店/未同步) */
        val menuUnavailable: Boolean get() = payload.optBoolean("menuUnavailable", false)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 線上拉最新並快取;回 ok/error。 */
    suspend fun refresh(storeId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(storeId, "UTF-8")
        val req = Request.Builder()
            .url("${BuildConfig.POS_URL}/api/pos/bootstrap?storeId=$enc")
            .header("User-Agent", "MacauPosNative/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})")
            .get()
            .build()
        val body = runCatching { http.newCall(req).execute().use { r -> r.body?.string() } }
            .getOrElse { return@withContext Result.failure(java.io.IOException("連唔到 server:${it.message}")) }
            ?: return@withContext Result.failure(java.io.IOException("server 無回應內容"))
        val json = runCatching { JSONObject(body) }.getOrElse {
            return@withContext Result.failure(IllegalStateException("server 回應唔係 JSON:${body.take(120)}"))
        }
        if (json.optBoolean("menuUnavailable", false)) {
            return@withContext Result.failure(IllegalStateException("呢間店仲未開放/未同步餐牌(menuUnavailable)"))
        }
        if (!json.has("menuItems") && !json.has("menu_items")) {
            return@withContext Result.failure(IllegalStateException("server 回應缺少餐牌內容:${json.keys().asSequence().take(6).joinToString(",")}"))
        }
        val storeName = json.optString("storeName", "")
        db.bootstrapCacheDao().put(
            BootstrapCacheEntity(
                storeId = storeId,
                storeName = storeName,
                currency = json.optString("currency", "MOP").takeIf { it.isNotBlank() },
                payload = json.toString(),
                sourceVersion = json.optLong("sourceVersion", 1),
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
