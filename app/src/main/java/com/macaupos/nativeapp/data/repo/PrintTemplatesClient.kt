package com.macaupos.nativeapp.data.repo

import com.macaupos.nativeapp.BuildConfig
import com.macaupos.nativeapp.data.DeviceStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 打印模板 —— 對齊 web `/api/pos/print-templates`(0027 `pos_print_templates`,一店一 row):
 *
 * - `GET ?storeId=` → `{ok, found, templates, shiftPresets, updatedAt}`
 * - `POST {storeId, templates}` → 整份模板 upsert
 *
 * 🔴 **round-trip 策略**:native 只改用戶揀嘅字串欄位(footerText/headerText/qrUrl),
 *    其餘 blocks/order/qrSize 等原樣帶返去 —— 避免漏欄位被 server 覆寫成預設。
 */
class PrintTemplatesClient(private val deviceStore: DeviceStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    data class Snapshot(val templates: JSONObject?, val found: Boolean, val updatedAt: String?)

    sealed class Result {
        data class Ok(val snapshot: Snapshot) : Result()
        data object Offline : Result()
        data class Fail(val error: String) : Result()
    }

    fun fetch(): Result {
        val storeId = deviceStore.storeId
        if (storeId.isBlank()) return Result.Fail("未登入(冇 storeId)")
        val req = Request.Builder()
            .url("${BuildConfig.POS_URL}/api/pos/print-templates?storeId=$storeId")
            .applyAuth()
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrNull()
                    ?: return Result.Fail("回應格式異常(HTTP ${resp.code})")
                if (!obj.optBoolean("ok", false)) return Result.Fail(obj.optString("error", "HTTP ${resp.code}"))
                Result.Ok(
                    Snapshot(
                        templates = obj.optJSONObject("templates"),
                        found = obj.optBoolean("found", false),
                        updatedAt = obj.optString("updatedAt", "").takeIf { it.isNotBlank() },
                    )
                )
            }
        } catch (e: Exception) {
            Result.Offline
        }
    }

    fun save(templates: JSONObject): kotlin.Result<Unit> {
        val body = JSONObject()
            .put("storeId", deviceStore.storeId)
            .put("templates", templates)
        val req = Request.Builder()
            .url("${BuildConfig.POS_URL}/api/pos/print-templates")
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
