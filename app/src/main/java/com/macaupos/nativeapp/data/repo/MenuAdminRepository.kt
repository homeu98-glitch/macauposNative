package com.macaupos.nativeapp.data.repo

import com.macaupos.nativeapp.BuildConfig
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.db.AppDatabase
import com.macaupos.nativeapp.data.db.BootstrapCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 餐牌管理寫入 —— 對齊 web `POST /api/pos/bootstrap`(見 route.ts):
 *
 * body:`{storeId, storeName, currency, categories, menuItems, tables, rules, printerGroups}`
 * 鑑權:`Authorization: Bearer <posDeviceToken>`(且 token.storeId 必須等於 storeId),
 *      冇配置強制鑑權時可以無 token(dev)。
 * 行為:server upsert `pos_bootstrap_config`(一店一 row)→ 本機同時更新 bootstrap_cache。
 *
 * ⚠️ 一定要**帶齊整份餐牌**(唔係 patch):server 係整個 row 覆寫,漏欄位就會清空。
 */
class MenuAdminRepository(
    private val deviceStore: DeviceStore,
    private val db: AppDatabase,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 目前餐牌(由本機快取讀,離線都用得)。 */
    suspend fun current(): JSONObject? = withContext(Dispatchers.IO) {
        val storeId = deviceStore.storeId
        if (storeId.isBlank()) return@withContext null
        val e = db.bootstrapCacheDao().get(storeId) ?: return@withContext null
        runCatching { JSONObject(e.payload) }.getOrNull()
    }

    /** 寫回 server + 更新本機快取。payload 必須係**完整**餐牌。 */
    suspend fun save(payload: JSONObject): Result<Unit> = withContext(Dispatchers.IO) {
        val storeId = deviceStore.storeId
        if (storeId.isBlank()) return@withContext Result.failure(IllegalStateException("未登入(冇 storeId)"))
        val body = JSONObject(payload.toString())
            .put("storeId", storeId)
        val req = Request.Builder()
            .url("${BuildConfig.POS_URL}/api/pos/bootstrap")
            .apply {
                val token = deviceStore.posDeviceToken
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
                header("Content-Type", "application/json")
            }
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        runCatching {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrNull()
                if (resp.code !in 200..299 || obj?.optBoolean("ok", false) != true) {
                    throw IllegalStateException(
                        obj?.optString("error") ?: "HTTP ${resp.code}:${text.take(120)}"
                    )
                }
            }
        }.onSuccess {
            // 本機快取同步(落單頁即刻見到新價)
            db.bootstrapCacheDao().put(
                BootstrapCacheEntity(
                    storeId = storeId,
                    storeName = payload.optString("storeName", ""),
                    currency = payload.optString("currency", "MOP"),
                    payload = payload.toString(),
                    sourceVersion = payload.optLong("sourceVersion", 1),
                    fetchedAt = System.currentTimeMillis(),
                )
            )
        }
    }
}
