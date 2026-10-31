package com.macaupos.nativeapp.data.repo

import android.content.Context
import com.macaupos.nativeapp.BuildConfig
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.db.AppDatabase
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 同步引擎 v1 —— 移植 web `sync-flush.ts` 語意:
 *
 * - 攞 outbox pending(≤200,server 上限 MAX_EVENTS_PER_REQUEST)
 * - `POST /api/pos/sync`,body = `{storeId, events:[{id,type,entityId,payload,status,createdAt,storeId}]}`
 * - 鑑權:`Authorization: Bearer <posDeviceToken>`(login session 帶嚟;web `posDeviceAuthHeaders()` 同款)
 * - 🔴 **網絡錯誤唔加 attempts**(web 同款:純抖動唔好燒 retry 配額);server 明確拒絕先 mark failed
 * - 23505(撞鍵)server 會 ack(true) —— 冪等安全
 *
 * reconcile(對帳守護)P1-B 接;呢度先行「送得出 → ack」主路。
 */
class SyncFlusher(context: Context, private val db: AppDatabase) {

    private val deviceStore = DeviceStore(context)
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    data class FlushResult(
        val sent: Int,
        val failed: Int,
        val error: String?,
    ) {
        val ok: Boolean get() = error == null
    }

    suspend fun flush(maxBatch: Int = 200): FlushResult {
        val pending = db.syncOutboxDao().takePending(maxBatch)
        if (pending.isEmpty()) return FlushResult(0, 0, null)

        val storeId = deviceStore.storeId
        val token = deviceStore.posDeviceToken
        if (storeId.isBlank() || token.isBlank()) {
            // 未登入/未帶憑證:唔好燒 attempts,等登入先
            return FlushResult(0, 0, "未登入,暫唔同步")
        }

        val events = JSONArray()
        pending.forEach { e ->
            events.put(
                JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("type", e.kind)
                    .put("entityId", entityIdOf(e.dedupeKey))
                    .put("payload", runCatching { JSONObject(e.payload) }.getOrElse { JSONObject() })
                    .put("status", "pending")
                    .put("createdAt", isoNow())
                    .put("storeId", storeId)
            )
        }

        val body = JSONObject().put("storeId", storeId).put("events", events)
        val req = Request.Builder()
            .url("${BuildConfig.POS_URL}/api/pos/sync")
            .post(body.toString().toRequestBody(json))
            .header("Authorization", "Bearer $token")
            .build()

        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrNull()
                if (resp.code in 200..299 && obj?.optBoolean("ok", false) == true) {
                    // 全部 ack(web 版 23505 都係 ack(true))→ 本地 mark done
                    pending.forEach { db.syncOutboxDao().markDone(it.id, System.currentTimeMillis()) }
                    // 同步成功嘅單更新本地 syncState(對帳守護用)
                    pending.filter { it.kind == "ORDER_CREATED" }.forEach {
                        db.posOrderDao().setSyncState(entityIdOf(it.dedupeKey), "synced")
                    }
                    FlushResult(pending.size, 0, null)
                } else {
                    // server 明確拒絕:mark failed(帶原因,UI 可重試)
                    val err = obj?.optString("error", "").orEmpty().ifBlank { "HTTP ${resp.code}" }
                    pending.forEach { db.syncOutboxDao().markFailed(it.id, err) }
                    FlushResult(0, pending.size, err)
                }
            }
        } catch (e: Exception) {
            // 網絡錯/逾時:保留 pending、唔加 attempts(web 同款),等下次 flush
            FlushResult(0, 0, "離線:${e.message ?: "網絡錯誤"}")
        }
    }

    private fun entityIdOf(dedupeKey: String): String =
        dedupeKey.substringAfter(':', dedupeKey)

    /** ISO now(同 OrderBuilder 同款格式;呢度 SimpleDateFormat 每次建,flush 頻率低無妨)。 */
    private fun isoNow(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date())
    }
}
