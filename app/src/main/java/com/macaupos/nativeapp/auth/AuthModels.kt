package com.macaupos.nativeapp.auth

import org.json.JSONObject

/**
 * 登入身份模型 —— 與 web `login-identities.ts` 嘅 `LoginIdentity` 一對一對齊。
 * (docs/160 §11、docs/161)
 */
data class LoginIdentity(
    val kind: String,          // "owner" | "staff"
    val id: String,
    val name: String,
    val staffNo: String?,      // staff 先有
    val role: String,          // "owner" | "manager" | "cashier"
) {
    companion object {
        fun fromJson(o: JSONObject): LoginIdentity = LoginIdentity(
            kind = o.optString("kind", "staff"),
            id = o.optString("id", ""),
            name = o.optString("name", ""),
            staffNo = o.optString("staffNo", "").takeIf { it.isNotBlank() },
            role = o.optString("role", "cashier"),
        )
    }
}

/** `POST /api/pos/login/identities` 嘅回應(parse 過)。 */
data class IdentitiesPayload(
    val ok: Boolean,
    val error: String?,
    val code: String?,
    val storeId: String?,
    val storeName: String?,
    val identities: List<LoginIdentity>,
) {
    /** 表未建立(0073)/未升級(0075)→ 應該落返帳號模式。🔴 唔可以靠 HTTP status 分辨(200 + ok:false)。 */
    val tableMissing: Boolean get() = code == "table_missing"
}

/**
 * 打 identities 嘅三種結果。
 *
 * 🔴 分開 [Offline] 係為了離線登入:只有**連唔到**(status 0,包括硬逾時)先可以落返本機憑證。
 *    server 明確回 ok:false 代表憑證被撤銷/店員被停用 —— 一定要照 server 講。
 */
sealed class IdentitiesResult {
    data class Ok(val payload: IdentitiesPayload) : IdentitiesResult()
    data class Fail(
        val error: String,
        val status: Int,
        val tableMissing: Boolean,
        val offline: Boolean,
    ) : IdentitiesResult()
}
