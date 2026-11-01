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
 * 打印中心 —— 對齊 web `/api/pos/print-jobs`:
 *
 * - `GET  /api/pos/print-jobs/status?storeId=` → `{ok, jobs:[{id,status,last_error,...}]}`
 *   (server 會順手 void 太舊嘅 stale job,所以拉一次就係對帳)
 * - `POST /api/pos/print-jobs/retry {jobId}` → 重試失敗單
 *
 * 鑑權:Bearer posDeviceToken。
 */
class PrintJobsClient(private val deviceStore: DeviceStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    data class Job(val id: String, val status: String, val lastError: String?)

    sealed class Result {
        data class Ok(val jobs: List<Job>) : Result()
        data object Offline : Result()
        data class Fail(val error: String) : Result()
    }

    fun list(): Result {
        val storeId = deviceStore.storeId
        if (storeId.isBlank()) return Result.Fail("未登入(冇 storeId)")
        val req = Request.Builder()
            .url("${BuildConfig.POS_URL}/api/pos/print-jobs/status?storeId=$storeId")
            .applyAuth()
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrNull()
                    ?: return Result.Fail("回應格式異常(HTTP ${resp.code})")
                if (!obj.optBoolean("ok", false)) return Result.Fail(obj.optString("error", "HTTP ${resp.code}"))
                val arr = obj.optJSONArray("jobs") ?: JSONArray()
                val jobs = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let {
                        Job(
                            id = it.optString("id"),
                            status = it.optString("status", "failed"),
                            lastError = it.optString("last_error", "").takeIf { e -> e.isNotBlank() },
                        )
                    }
                }
                Result.Ok(jobs)
            }
        } catch (e: Exception) {
            Result.Offline
        }
    }

    fun retry(jobId: String): kotlin.Result<Unit> {
        val body = JSONObject().put("jobId", jobId).put("storeId", deviceStore.storeId)
        val req = Request.Builder()
            .url("${BuildConfig.POS_URL}/api/pos/print-jobs/retry")
            .applyAuth()
            .post(body.toString().toRequestBody(json))
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrNull()
                if (resp.code in 200..299 && obj?.optBoolean("ok", false) == true) kotlin.Result.success(Unit)
                else kotlin.Result.failure(
                    IllegalStateException(obj?.optString("error") ?: "HTTP ${resp.code}")
                )
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
