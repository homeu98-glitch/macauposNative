package com.macaupos.nativeapp.auth

import android.content.Context
import com.macaupos.nativeapp.data.JsonStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/**
 * 離線登入憑證 —— 1:1 對齊 web `offline-credential.ts`(P4)嘅安全模型:
 *
 * - **存本機驗證器,唔存 server 嘅 pinHash/pinSalt**:每次聯網登入成功,
 *   用**本機隨機 salt** 重新派生 `PBKDF2-SHA256(pin, salt, 120k)` 存落嚟。
 *   dump 走部機都只影響「呢部機、呢個員工」嘅 4 位 PIN。
 * - **7 日 TTL**:過期一定要聯網登入一次(跌落/被盜嘅機最多用 7 日)。
 * - **錯 5 次 → 鎖 5 分鐘**:計數存喺憑證入面,refresh 都唔會重設。
 * - **逐身份分開 session**:唔可以有「全店共用一份」—— 防止店員離線登入
 *   靜靜雞得到店主權限。
 * - **只有「連唔到 server」先走離線**;server 明確拒絕一定照 server 講。
 */
class OfflineCredential(context: Context) {

    private val store = JsonStore(context, "offline-credential.json")

    // ---- 常量(與 web 版數值一致)----

    companion object {
        const val VERSION = 1
        const val TTL_MS: Long = 7L * 24 * 60 * 60 * 1000
        const val MAX_FAILS = 5
        const val LOCK_MS: Long = 5 * 60 * 1000
    }

    // ---- 資料結構 ----

    data class Entry(
        val kind: String,          // "owner" | "staff"
        val id: String,
        val name: String,
        val staffNo: String?,
        val role: String,
        val saltHex: String,
        val hashHex: String,
        val session: JSONObject,   // web AuthSession 原樣
        val savedAt: Long,
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean = now - savedAt > TTL_MS
    }

    data class VerifyResult(
        val ok: Boolean,
        val entry: Entry?,
        val error: String?,          // 畀 UI 顯示嘅原因
        val retryAfterSec: Int? = null,
    )

    // ---- 寫入(聯網登入成功後 call)----

    /**
     * 記低一個身份 + 本機派生驗證器。
     * @param pin 明文 PIN(用完即棄,唔落 disk)
     */
    fun save(identity: LoginIdentity, pin: String, session: JSONObject) {
        val rnd = SecureRandom()
        val salt = ByteArray(Pbkdf2.SALT_BYTES).also { rnd.nextBytes(it) }
        val hash = Pbkdf2.derive(pin.toByteArray(Charsets.UTF_8), salt)

        val root = store.read() ?: JSONObject().put("version", VERSION)
        val arr = root.optJSONArray("identities") ?: JSONArray()
        // 同一個身份重登 → 覆蓋舊驗證器(salt 都換)
        val kept = (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it) }
            .filter { it.optString("id") != identity.id }
        arr.let { arr2 ->
            // JSONArray 冇 removeAll,重建
            val fresh = JSONArray()
            kept.forEach { fresh.put(it) }
            fresh.put(
                JSONObject()
                    .put("kind", identity.kind)
                    .put("id", identity.id)
                    .put("name", identity.name)
                    .put("staffNo", identity.staffNo ?: "")
                    .put("role", identity.role)
                    .put("algo", Pbkdf2.ALGO)
                    .put("salt", Pbkdf2.toHex(salt))
                    .put("hash", Pbkdf2.toHex(hash))
                    .put("session", session)
                    .put("savedAt", System.currentTimeMillis())
                    .put("failCount", 0)
                    .put("lockedUntil", 0)
            )
            root.put("version", VERSION)
            root.put("identities", fresh)
        }
        store.write(root)
    }

    // ---- 讀取 ----

    fun list(): List<Entry> {
        val arr = store.read()?.optJSONArray("identities") ?: return emptyList()
        return (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it) }
            .map { o ->
                Entry(
                    kind = o.optString("kind"),
                    id = o.optString("id"),
                    name = o.optString("name"),
                    staffNo = o.optString("staffNo", "").takeIf { it.isNotBlank() },
                    role = o.optString("role"),
                    saltHex = o.optString("salt"),
                    hashHex = o.optString("hash"),
                    session = o.optJSONObject("session") ?: JSONObject(),
                    savedAt = o.optLong("savedAt", 0),
                )
            }
    }

    fun get(id: String): Entry? = list().firstOrNull { it.id == id }

    /** 有冇**未過期**嘅本地憑證(決定斷網時准唔准離線登入)。 */
    fun hasUsable(now: Long = System.currentTimeMillis()): Boolean =
        list().any { !it.isExpired(now) }

    // ---- 離線驗證 ----

    /** 離線 PIN 驗證(含鎖定 + TTL)。只喺「連唔到 server」時 call。 */
    fun verifyOffline(id: String, pin: String): VerifyResult {
        val now = System.currentTimeMillis()
        val root = store.read() ?: return VerifyResult(false, null, "本機冇離線憑證")
        val arr = root.optJSONArray("identities") ?: return VerifyResult(false, null, "本機冇離線憑證")

        var target: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) { target = o; break }
        }
        val o = target ?: return VerifyResult(false, null, "本機冇呢個身份嘅憑證")

        val lockedUntil = o.optLong("lockedUntil", 0)
        if (lockedUntil > now) {
            return VerifyResult(
                false, null,
                "錯太多次,鎖定中(仲有 ${(lockedUntil - now + 59_999) / 60_000} 分鐘)",
                retryAfterSec = (((lockedUntil - now) + 999) / 1000).toInt(),
            )
        }

        val entry = Entry(
            kind = o.optString("kind"),
            id = o.optString("id"),
            name = o.optString("name"),
            staffNo = o.optString("staffNo", "").takeIf { it.isNotBlank() },
            role = o.optString("role"),
            saltHex = o.optString("salt"),
            hashHex = o.optString("hash"),
            session = o.optJSONObject("session") ?: JSONObject(),
            savedAt = o.optLong("savedAt", 0),
        )

        if (entry.isExpired(now)) {
            return VerifyResult(false, null, "離線憑證已過期(7 日),請聯網登入一次")
        }

        val hash = Pbkdf2.derive(pin.toByteArray(Charsets.UTF_8), Pbkdf2.fromHex(entry.saltHex))
        if (Pbkdf2.toHex(hash) == entry.hashHex) {
            // 成功 → 重設 fail 計數(鎖定都清埋:正確 PIN 一定係本人)。
            // JSONObject 係 live reference,改 o 即改到 arr 入面嗰個,直接持久化就得。
            o.put("failCount", 0)
            o.put("lockedUntil", 0)
            store.write(root)
            return VerifyResult(true, entry, null)
        }

        // 失敗 → 計數 + 鎖
        val fails = o.optInt("failCount", 0) + 1
        o.put("failCount", fails)
        var retry: Int? = null
        if (fails >= MAX_FAILS) {
            val until = now + LOCK_MS
            o.put("lockedUntil", until)
            retry = (LOCK_MS / 1000).toInt()
        }
        store.write(root)
        val left = MAX_FAILS - fails
        return VerifyResult(
            false, null,
            if (left > 0) "PIN 錯誤(仲可以試 $left 次)" else "PIN 錯誤,已鎖定 5 分鐘",
            retryAfterSec = retry,
        )
    }

    /** 清空(登出/換店)。 */
    fun clear() = store.clear()
}
