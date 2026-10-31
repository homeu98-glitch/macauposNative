package com.macaupos.nativeapp

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.macaupos.nativeapp.auth.AuthClient
import com.macaupos.nativeapp.auth.IdentitiesResult
import com.macaupos.nativeapp.auth.OfflineCredential
import com.macaupos.nativeapp.data.DeviceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * P0 登入骨架 —— 對齊 web 版 `/login` 嘅協議:
 *
 * 1. 打 8 位店舖帳號 → `POST /api/pos/login/identities`(5s 硬逾時)
 * 2. 連唔到 + 本機有憑證 → **離線模式**(本機 PBKDF2 驗證器,鎖定/TTL 同 web P4 一致)
 * 3. 店主 → `POST /api/ledger/login`;店員 → `POST /api/pos/login/identity`(P0 下半段接裝置綁定)
 * 4. 成功 → 本機派生驗證器存檔 → Home
 *
 * 裝置綁定(deviceId/deviceSecret)喺 P0 下半段接:店主首次登入後註冊裝置,
 * 之後呢部機行「裝置模式」,店員唔使再打 8 位帳號(同 web docs/161 一致)。
 */
class MainActivity : ComponentActivity() {

    private val auth = AuthClient()
    private lateinit var deviceStore: DeviceStore
    private lateinit var offline: OfflineCredential

    private lateinit var inputAccount: EditText
    private lateinit var inputPin: EditText
    private lateinit var btnLogin: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtOfflineBadge: TextView

    /** 離線模式 flag:identities 查詢失敗且本機有憑證時開啟。 */
    private var offlineMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceStore = DeviceStore(this)
        offline = OfflineCredential(this)
        setContentView(R.layout.activity_login)

        inputAccount = findViewById(R.id.inputAccount)
        inputPin = findViewById(R.id.inputPin)
        btnLogin = findViewById(R.id.btnLogin)
        txtStatus = findViewById(R.id.txtStatus)
        txtOfflineBadge = findViewById(R.id.txtOfflineBadge)

        // 已綁定嘅機:帳號預填(裝置模式其實唔使帳號,呢度顯示返上次嘅店方便核對)
        if (deviceStore.isBound()) {
            inputAccount.setText(deviceStore.storeAccount)
            txtStatus.text = "已綁定裝置:${deviceStore.storeName.ifBlank { deviceStore.storeId }}"
        } else if (offline.hasUsable()) {
            txtOfflineBadge.visibility = android.view.View.VISIBLE
            txtStatus.text = "本機有離線憑證(斷網都可以登入)"
        }

        btnLogin.setOnClickListener { doLogin() }
    }

    private fun doLogin() {
        val account = inputAccount.text.toString().trim()
        val pin = inputPin.text.toString().trim()

        if (pin.length != 4) {
            txtStatus.text = "請輸入 4 位 PIN"
            return
        }
        hideKeyboard()

        // ── 離線模式:identities 之前已確認連唔到,直接本機驗證 ──
        if (offlineMode) {
            doOfflineVerify(account, pin)
            return
        }

        txtStatus.text = "連接伺服器…"
        btnLogin.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                auth.fetchIdentities(deviceStore.deviceId, deviceStore.deviceSecret, account)
            }
            btnLogin.isEnabled = true
            when (result) {
                is IdentitiesResult.Ok -> {
                    val payload = result.payload
                    // P0 骨架:身份清單已確認,取 owner(或唯一身份)行登入。
                    // P0 下半段改成身份卡選擇 UI(對齊 web /login 嘅 identity cards)。
                    val identity = payload.identities.firstOrNull { it.kind == "owner" }
                        ?: payload.identities.firstOrNull()
                    if (identity == null) {
                        txtStatus.text = "呢間店未有可用身份"
                        return@launch
                    }
                    if (account.isNotBlank()) deviceStore.storeAccount = account
                    payload.storeId?.let { deviceStore.storeId = it }
                    payload.storeName?.let { deviceStore.storeName = it }

                    txtStatus.text = "登入中…"
                    val resp = withContext(Dispatchers.IO) {
                        runCatching {
                            if (identity.kind == "owner") {
                                auth.ownerLogin(account, pin, deviceStore.deviceId, deviceStore.deviceSecret)
                            } else {
                                auth.staffLogin(deviceStore.deviceId, deviceStore.deviceSecret, identity.staffNo ?: "", pin)
                            }
                        }.getOrElse { JSONObject().put("ok", false).put("error", "網絡錯誤:${it.message}").put("_httpStatus", 0) }
                    }
                    handleLoginResponse(resp, identity, pin)
                }

                is IdentitiesResult.Fail -> {
                    if (result.offline && offline.hasUsable()) {
                        offlineMode = true
                        txtOfflineBadge.visibility = android.view.View.VISIBLE
                        txtStatus.text = "連唔到伺服器 → 離線模式(請再打一次 PIN)"
                    } else {
                        txtStatus.text = if (result.offline) {
                            "連唔到伺服器,而且本機冇離線憑證。首次使用必須聯網登入。"
                        } else {
                            "登入失敗:${result.error}${if (result.tableMissing) "(資料表未建立,請聯絡管理員)" else ""}"
                        }
                    }
                }
            }
        }
    }

    private fun doOfflineVerify(account: String, pin: String) {
        val entries = offline.list().filter { !it.isExpired() }
        val target = if (account.isBlank()) entries.firstOrNull() else {
            entries.firstOrNull { it.kind == "owner" } ?: entries.firstOrNull()
        }
        if (target == null) {
            txtStatus.text = "本機離線憑證已過期,請聯網登入一次"
            offlineMode = false
            return
        }
        val result = offline.verifyOffline(target.id, pin)
        if (result.ok && result.entry != null) {
            Toast.makeText(this, "離線登入:${result.entry.name}", Toast.LENGTH_SHORT).show()
            goHome(offline = true, name = result.entry.name)
        } else {
            txtStatus.text = result.error ?: "離線驗證失敗"
            result.retryAfterSec?.let { sec ->
                btnLogin.isEnabled = false
                txtStatus.postDelayed({ btnLogin.isEnabled = true }, sec * 1000L)
            }
        }
    }

    private fun handleLoginResponse(resp: org.json.JSONObject, identity: com.macaupos.nativeapp.auth.LoginIdentity, pin: String) {
        val ok = resp.optBoolean("ok", false)
        if (!ok) {
            val status = resp.optInt("_httpStatus", 0)
            txtStatus.text = if (status == 0) {
                // 連唔到 → 若本機有憑證可離線
                if (offline.hasUsable()) {
                    offlineMode = true
                    txtOfflineBadge.visibility = android.view.View.VISIBLE
                    "連唔到伺服器 → 離線模式(請再打一次 PIN)"
                } else {
                    "連唔到伺服器:首次使用必須聯網登入"
                }
            } else {
                "登入失敗:${resp.optString("error", "未知錯誤")}"
            }
            return
        }
        // 成功:本機重新派生驗證器(唔存 server 嘅 pinHash)
        offline.save(identity, pin, resp.optJSONObject("session") ?: org.json.JSONObject())
        deviceStore.storeId = resp.optString("storeId", deviceStore.storeId)
        deviceStore.storeName = resp.optString("storeName", deviceStore.storeName)
        // server 可能喺登入成功時寫入裝置授權
        resp.optString("deviceId", "").takeIf { it.isNotBlank() }?.let { deviceStore.deviceId = it }
        resp.optString("deviceSecret", "").takeIf { it.isNotBlank() }?.let { deviceStore.deviceSecret = it }

        goHome(offline = false, name = identity.name)
    }

    private fun goHome(offline: Boolean, name: String) {
        val i = Intent(this, HomeActivity::class.java)
            .putExtra("operator", name)
            .putExtra("offline", offline)
        startActivity(i)
        finish()
    }

    private fun hideKeyboard() {
        runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        }
    }
}
