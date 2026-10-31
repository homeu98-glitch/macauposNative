package com.macaupos.nativeapp.auth

import com.macaupos.nativeapp.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 認證 client —— 直接打 web 版（Vercel）現有端點,**同一套安全規則**:
 *
 * - `POST /api/pos/login/identities` —— 身份清單（裝置模式 or 帳號模式）
 * - `POST /api/pos/login/identity`   —— 店員 PIN 登入（需要已綁定裝置）
 * - `POST /api/ledger/login`         —— 店主登入（8 位帳號 + PIN）
 *
 * 對齊 web `login-identities.ts`:
 * - identities 查詢 **5 秒硬逾時**（斷網唔可以卡住成個登入頁）
 * - `table_missing` 係 200 + ok:false + code —— 一定要讀 `code`
 * - `status == 0`（連唔到 / 硬逾時）先算 offline,先可以落本機憑證
 */
class AuthClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    /**
     * 查身份清單。兩種入參:
     *  - 裝置模式:`deviceId` + `deviceSecret`（已初始化嘅機,唔使打 8 位帳號）
     *  - 帳號模式:`account`（新機/撤銷過,店主打 8 位帳號）
     */
    fun fetchIdentities(deviceId: String, deviceSecret: String, account: String): IdentitiesResult {
        val body = JSONObject()
        if (account.isNotBlank()) body.put("account", account)
        if (deviceId.isNotBlank()) body.put("deviceId", deviceId)
        if (deviceSecret.isNotBlank()) body.put("deviceSecret", deviceSecret)

        val req = Request.Builder()
            .url("${BuildConfig.POS_URL}/api/pos/login/identities")
            .post(body.toString().toRequestBody(json))
            .build()

        return runCatching {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrNull()
                if (obj == null) {
                    IdentitiesResult.Fail("伺服器回應格式異常（HTTP ${resp.code}）", resp.code, tableMissing = false, offline = false)
                } else {
                    parsePayload(obj)
                }
            }
        }.getOrElse { e ->
            // connect/timeout/socket 全部歸類 offline → 允許落本機憑證
            IdentitiesResult.Fail(e.message ?: "網絡錯誤", 0, tableMissing = false, offline = true)
        }
    }

    /**
     * 店員 PIN 登入。需要已綁定裝置;server 會驗裝置憑證 → 驗 PIN → 成功後寫入裝置授權。
     * 回:ok 時帶 session JSON(存入 OfflineCredential);fail 時帶 error/code/retryAfterSec。
     */
    fun staffLogin(deviceId: String, deviceSecret: String, staffNo: String, pin: String): JSONObject {
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("deviceSecret", deviceSecret)
            .put("staffNo", staffNo)
            .put("pin", pin)
        return postJson("${BuildConfig.POS_URL}/api/pos/login/identity", body)
    }

    /** 店主登入(8 位帳號 + PIN)。成功回 session JSON + storeId/storeName。 */
    fun ownerLogin(account: String, pin: String, deviceId: String, deviceSecret: String): JSONObject {
        val body = JSONObject()
            .put("account", account)
            .put("pin", pin)
        if (deviceId.isNotBlank()) body.put("deviceId", deviceId)
        if (deviceSecret.isNotBlank()) body.put("deviceSecret", deviceSecret)
        return postJson("${BuildConfig.POS_URL}/api/ledger/login", body)
    }

    // ---- 內部 ----

    private fun parsePayload(obj: JSONObject): IdentitiesResult {
        val ok = obj.optBoolean("ok", true)
        if (!ok) {
            return IdentitiesResult.Fail(
                error = obj.optString("error", "登入失敗"),
                status = 200,
                tableMissing = obj.optString("code", "") == "table_missing",
                offline = false,
            )
        }
        val arr = obj.optJSONArray("identities") ?: JSONArray()
        val identities = (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it) }
            .map { LoginIdentity.fromJson(it) }
        return IdentitiesResult.Ok(
            IdentitiesPayload(
                ok = true,
                error = null,
                code = obj.optString("code", "").takeIf { it.isNotBlank() },
                storeId = obj.optString("storeId", "").takeIf { it.isNotBlank() },
                storeName = obj.optString("storeName", "").takeIf { it.isNotBlank() },
                identities = identities,
            )
        )
    }

    /** 統一 POST JSON;回 parse 過嘅 JSONObject(不論 ok/fail,交俾 caller 判斷)。連唔到就 throw。 */
    private fun postJson(url: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(json))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val obj = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("伺服器回應格式異常（HTTP ${resp.code}）")
            }
            // 保持 HTTP code 喺 result 入面,方便 caller 分「連唔到」定「server 拒絕」
            obj.put("_httpStatus", resp.code)
            return obj
        }
    }
}
