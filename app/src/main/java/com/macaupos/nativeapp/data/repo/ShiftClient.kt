package com.macaupos.nativeapp.data.repo

import com.macaupos.nativeapp.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 交班 client —— 對齊 web `/api/pos/shift`:
 *
 * - `GET  /api/pos/shift?storeId=`         → `{ok, active?:shift, serverNow}`
 * - `GET  ...&history=1&limit=60`          → `{ok, history:[shift]}`
 * - `POST {action:"open",  storeId, employeeAccount, employeeName, openingNote}`
 *   → `{ok, conflict:bool, active}`(已有 active 時 conflict=true,以現有為準)
 * - `POST {action:"close", storeId, closingNote, actualCash, cashDifference, summary}`
 *   → `{ok, closed}`;404 code=no_active_shift = 另一部機已收工
 *
 * shift 欄位:`id/storeId/employeeAccount/employeeName/openedAt/closedAt/
 * openingNote/closingNote/actualCash/cashDifference/summary`
 * 鑑權:`Authorization: Bearer <posDeviceToken>`(登入 session 帶)
 */
class ShiftClient(private val deviceStore: com.macaupos.nativeapp.data.DeviceStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    data class Shift(
        val id: String,
        val employeeAccount: String,
        val employeeName: String,
        val openedAt: String,
        val closedAt: String?,
        val openingNote: String?,
        val closingNote: String?,
        val actualCash: Double?,
        val cashDifference: Double?,
        val summary: JSONObject?,
    )

    sealed class Result {
        data class Ok(val active: Shift?, val conflict: Boolean = false) : Result()
        /** 連唔到(offline) —— 唔可以當成功 */
        data object Offline : Result()
        data class Fail(val error: String) : Result()
    }

    fun activeShift(): Result {
        val storeId = deviceStore.storeId
        if (storeId.isBlank()) return Result.Fail("未登入(冇 storeId)")
        val req = Request.Builder()
            .url("${BuildConfig.POS_URL}/api/pos/shift?storeId=$storeId")
            .applyAuth()
            .get()
            .build()
        return exec(req) { obj ->
            val active = obj.optJSONObject("active")?.let { parseShift(it) }
            Result.Ok(active)
        }
    }

    fun open(employeeAccount: String, employeeName: String, note: String?): Result {
        val body = JSONObject()
            .put("action", "open")
            .put("storeId", deviceStore.storeId)
            .put("employeeAccount", employeeAccount)
            .put("employeeName", employeeName)
        note?.takeIf { it.isNotBlank() }?.let { body.put("openingNote", it) }
        return post(body) { obj ->
            Result.Ok(obj.optJSONObject("active")?.let { parseShift(it) }, obj.optBoolean("conflict", false))
        }
    }

    fun close(closingNote: String?, actualCash: Double?, cashDifference: Double?, summary: JSONObject?): Result {
        val body = JSONObject()
            .put("action", "close")
            .put("storeId", deviceStore.storeId)
        closingNote?.takeIf { it.isNotBlank() }?.let { body.put("closingNote", it) }
        actualCash?.let { body.put("actualCash", it) }
        cashDifference?.let { body.put("cashDifference", it) }
        summary?.let { body.put("summary", it) }
        return post(body) { obj ->
            Result.Ok(obj.optJSONObject("closed")?.let { parseShift(it) })
        }
    }

    // ---- 內部 ----

    private fun Request.Builder.applyAuth(): Request.Builder {
        val token = deviceStore.posDeviceToken
        if (token.isNotBlank()) header("Authorization", "Bearer $token")
        return header("Content-Type", "application/json")
    }

    private fun post(body: JSONObject, map: (JSONObject) -> Result): Result {
        if (deviceStore.storeId.isBlank()) return Result.Fail("未登入(冇 storeId)")
        val req = Request.Builder()
            .url("${BuildConfig.POS_URL}/api/pos/shift")
            .applyAuth()
            .post(body.toString().toRequestBody(json))
            .build()
        return exec(req, map)
    }

    private fun exec(req: Request, map: (JSONObject) -> Result): Result {
        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrNull()
                    ?: return Result.Fail("伺服器回應格式異常(HTTP ${resp.code})")
                if (!obj.optBoolean("ok", false)) {
                    val code = obj.optString("code", "")
                    val msg = obj.optString("error", "HTTP ${resp.code}")
                    return Result.Fail(if (code == "no_active_shift") "另一部機已收工(冇進行中班次)" else msg)
                }
                map(obj)
            }
        } catch (e: Exception) {
            Result.Offline
        }
    }

    private fun parseShift(o: JSONObject) = Shift(
        id = o.optString("id"),
        employeeAccount = o.optString("employeeAccount"),
        employeeName = o.optString("employeeName"),
        openedAt = o.optString("openedAt"),
        closedAt = o.optString("closedAt", "").takeIf { it.isNotBlank() },
        openingNote = o.optString("openingNote", "").takeIf { it.isNotBlank() },
        closingNote = o.optString("closingNote", "").takeIf { it.isNotBlank() },
        actualCash = if (o.has("actualCash") && !o.isNull("actualCash")) o.optDouble("actualCash") else null,
        cashDifference = if (o.has("cashDifference") && !o.isNull("cashDifference")) o.optDouble("cashDifference") else null,
        summary = o.optJSONObject("summary"),
    )
}
