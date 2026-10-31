package com.macaupos.nativeapp.supabase

import com.macaupos.nativeapp.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Supabase PostgREST 薄 client —— native 同 web 讀寫**同一個 DB、同一批表**。
 *
 * 設計原則(對齊計劃書 §4):
 * - 資料面(訂單/餐牌/桌台/班次/外送 job/打印模板)native **直接寫 Supabase**,
 *   同 web 版行為一致 —— 呢個就係「native 做完就更新到 DB,同 website 一樣」。
 * - 安全面(登入/設備綁定/ledger 結算)**唔經呢度**,行 `AuthClient`(Vercel /api,
 *   server 端安全邏輯唔搬落 client)。
 * - 唔引 supabase-kt:依賴極簡,且 PostgREST 介面本身就係 HTTP。
 *
 * 用法:
 * ```
 * Supabase.from("pos_orders")
 *     .select("store_id=eq.$storeId&order=created_at.desc")
 *     .limit(50)
 * supabase.insert("pos_orders", orderJson)
 * ```
 */
object Supabase {

    private val baseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    class Result(
        val code: Int,
        val body: String,
        val offline: Boolean = false,
    ) {
        val ok: Boolean get() = code in 200..299
        fun jsonArray(): JSONArray = JSONArray(body)
        fun jsonObject(): JSONObject = JSONObject(body)
    }

    fun isConfigured(): Boolean = baseUrl.startsWith("https://") && anonKey.isNotBlank()

    // ---- builder ----

    class Table internal constructor(private val client: Supabase, private val name: String) {
        private val params = mutableListOf<Pair<String, String>>()
        private val headers = mutableListOf<Pair<String, String>>()

        /** PostgREST filter,例:`store_id=eq.xxx&order=created_at.desc` —— `&` 分隔多條。 */
        fun where(query: String): Table {
            query.split("&").filter { it.contains("=") }.forEach {
                val i = it.indexOf('=')
                params.add(it.substring(0, i) to it.substring(i + 1))
            }
            return this
        }

        fun limit(n: Int): Table { params.add("limit" to n.toString()); return this }
        fun offset(n: Int): Table { params.add("offset" to n.toString()); return this }
        fun header(k: String, v: String): Table { headers.add(k to v); return this }

        fun select(): Result = client.doGet(name, params, headers)
        fun insert(obj: JSONObject, upsert: Boolean = false): Result = client.doInsert(name, obj, upsert, headers)
        fun insertMany(arr: JSONArray, upsert: Boolean = false): Result = client.doInsert(name, arr, upsert, headers)
        fun update(patch: JSONObject): Result = client.doPatch(name, params, patch, headers)
        fun delete(): Result = client.doDelete(name, params, headers)
    }

    fun from(table: String): Table = Table(this, table)

    /** RPC(Postgres function),例:`set_order_paid_in_store`。 */
    fun rpc(function: String, args: JSONObject): Result {
        val req = Request.Builder()
            .url("$baseUrl/rest/v1/rpc/$function")
            .post(args.toString().toRequestBody(json))
            .apply { commonHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        return exec(req)
    }

    // ---- 內部 ----

    private fun commonHeaders(): List<Pair<String, String>> {
        if (anonKey.isBlank()) {
            throw IllegalStateException("SUPABASE_ANON_KEY 未設定:請喺 local.properties 加 supabase.anonKey=<key> 再重 build")
        }
        return listOf(
            "apikey" to anonKey,
            "Authorization" to "Bearer $anonKey",
        )
    }

    private fun doGet(table: String, params: List<Pair<String, String>>, extra: List<Pair<String, String>>): Result {
        val url = "$baseUrl/rest/v1/$table".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).get().apply {
            commonHeaders().forEach { (k, v) -> header(k, v) }
            extra.forEach { (k, v) -> header(k, v) }
        }.build()
        return exec(req)
    }

    private fun doInsert(table: String, body: Any, upsert: Boolean, extra: List<Pair<String, String>>): Result {
        val req = Request.Builder()
            .url("$baseUrl/rest/v1/$table")
            .post(body.toString().toRequestBody(json))
            .apply {
                commonHeaders().forEach { (k, v) -> header(k, v) }
                extra.forEach { (k, v) -> header(k, v) }
                if (upsert) header("Prefer", "resolution=merge-duplicates,return=representation")
                else header("Prefer", "return=representation")
            }
            .build()
        return exec(req)
    }

    private fun doPatch(table: String, params: List<Pair<String, String>>, patch: JSONObject, extra: List<Pair<String, String>>): Result {
        val url = "$baseUrl/rest/v1/$table".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).patch(patch.toString().toRequestBody(json)).apply {
            commonHeaders().forEach { (k, v) -> header(k, v) }
            extra.forEach { (k, v) -> header(k, v) }
            header("Prefer", "return=representation")
        }.build()
        return exec(req)
    }

    private fun doDelete(table: String, params: List<Pair<String, String>>, extra: List<Pair<String, String>>): Result {
        val url = "$baseUrl/rest/v1/$table".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).delete().apply {
            commonHeaders().forEach { (k, v) -> header(k, v) }
            extra.forEach { (k, v) -> header(k, v) }
        }.build()
        return exec(req)
    }

    private fun exec(req: Request): Result = runCatching {
        http.newCall(req).execute().use { resp ->
            Result(resp.code, resp.body?.string().orEmpty())
        }
    }.getOrElse { Result(0, "", offline = true) }
}
