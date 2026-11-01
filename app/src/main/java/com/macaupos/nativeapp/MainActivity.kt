package com.macaupos.nativeapp

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.macaupos.nativeapp.auth.AuthClient
import com.macaupos.nativeapp.auth.IdentitiesResult
import com.macaupos.nativeapp.auth.LoginIdentity
import com.macaupos.nativeapp.auth.OfflineCredential
import com.macaupos.nativeapp.data.DeviceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * P0 登入 —— 1:1 對齊 web `/login` 協議(docs/160 §11、docs/161、docs/162):
 *
 * **裝置模式**(已綁定):`deviceId+deviceSecret` 查身份 → 揀卡 → PIN
 *   - 店主卡 → `POST /api/ledger/login`(唔使帳號,server 由裝置帶出)
 *   - 店員卡 → `POST /api/pos/login/identity`
 * **帳號模式**(新機):打 8 位店舖帳號 → 查身份 → 店主 PIN 登入
 *   → server `upsertBoundDevice` 寫入授權 → 回應 `deviceBinding` 存本機
 *   ⇒ 之後呢部機永遠行裝置模式,店員唔使再打帳號。
 *
 * **離線模式**:identities 連唔到(status 0)+ 本機有憑證 → 本機 PBKDF2 驗證器。
 *   server 明確拒絕(撤銷/停用)一定照 server 講,唔會用快取放行。
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
    private lateinit var txtMode: TextView

    private var offlineMode = false
    /** 本輪登入用嘅身份(identities 查詢後揀定;離線模式由本機憑證清單揀)。 */
    private var pendingIdentities: List<LoginIdentity> = emptyList()

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
        txtMode = findViewById(R.id.txtMode)

        if (deviceStore.isBound()) {
            // 裝置模式:唔使帳號,鎖定帳號輸入
            inputAccount.setText(deviceStore.storeAccount)
            inputAccount.isEnabled = false
            txtMode.text = "已綁定:${deviceStore.storeName.ifBlank { "店舖 " + deviceStore.storeId.take(8) }}"
            if (offline.hasUsable()) txtOfflineBadge.visibility = View.VISIBLE
        } else if (offline.hasUsable()) {
            txtOfflineBadge.visibility = View.VISIBLE
            txtStatus.text = "本機有離線憑證(斷網都可以登入)"
        }

        btnLogin.setOnClickListener { doLogin() }

        // debug 專用 UI 預覽:用示範餐牌直接入落單頁,方便同 web 逐格對照(唔使真帳號)
        if (BuildConfig.DEBUG) {
            findViewById<TextView>(R.id.txtDebugPreview).apply {
                visibility = View.VISIBLE
                setOnClickListener { seedDemoAndPreview() }
            }
        }
    }

    /** 寫入示範 bootstrap 快取(對齊 J 截圖菜單),然後開落單頁。 */
    private fun seedDemoAndPreview() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val storeId = deviceStore.storeId.ifBlank { "demo-store" }
                    deviceStore.storeId = storeId
                    deviceStore.storeName = "老智牛肉麵 [示範]"
                    deviceStore.storeAccount = "60000002"

                    val cats = org.json.JSONArray()
                        .put(JSONObject().put("id", "c1").put("name", "牛肉麵系列"))
                        .put(JSONObject().put("id", "c2").put("name", "家常系列"))
                        .put(JSONObject().put("id", "c3").put("name", "湯品"))
                        .put(JSONObject().put("id", "c4").put("name", "飲品"))

                    fun item(id: String, name: String, price: Double, cat: String) = JSONObject()
                        .put("id", id).put("name", name).put("price", price)
                        .put("categoryId", cat).put("printerGroup", "kitchen")

                    val items = org.json.JSONArray()
                        .put(item("m1", "大麥克牛肉麵", 100.0, "c1"))
                        .put(item("m2", "酸菜牛肉麵", 75.0, "c1"))
                        .put(item("m3", "人氣半筋半肉麵", 85.0, "c1"))
                        .put(item("m4", "招牌牛三寶", 95.0, "c1"))
                        .put(item("m5", "牛肋條麵", 65.0, "c1"))
                        .put(item("m6", "牛筋麵", 60.0, "c1"))
                        .put(item("m7", "牛肚麵", 60.0, "c1"))
                        .put(item("m8", "原汁牛清湯麵", 30.0, "c3"))

                    val tables = org.json.JSONArray()
                        .put(JSONObject().put("id", "t1").put("name", "A01").put("area", "一樓"))
                        .put(JSONObject().put("id", "t2").put("name", "A02").put("area", "一樓"))
                        .put(JSONObject().put("id", "t3").put("name", "A03").put("area", "一樓"))

                    val payload = JSONObject()
                        .put("storeId", storeId)
                        .put("storeName", "老智牛肉麵 [示範]")
                        .put("currency", "MOP")
                        .put("categories", cats)
                        .put("menuItems", items)
                        .put("tables", tables)
                        .put("rules", JSONObject().put("taxRate", 0.0).put("serviceChargeRate", 0.0))

                    com.macaupos.nativeapp.data.db.AppDatabase.get(this@MainActivity)
                        .bootstrapCacheDao()
                        .put(
                            com.macaupos.nativeapp.data.db.BootstrapCacheEntity(
                                storeId = storeId,
                                storeName = "老智牛肉麵 [示範]",
                                currency = "MOP",
                                payload = payload.toString(),
                                sourceVersion = 1,
                                fetchedAt = System.currentTimeMillis(),
                            )
                        )
                }
                // debug:入 Home(唔使帳號),再由 Home 撳「落單」睇餐牌
                startActivity(
                    Intent(this@MainActivity, HomeActivity::class.java)
                        .putExtra("operator", "預覽")
                        .putExtra("offline", true)
                )
            } catch (e: Exception) {
                txtStatus.text = "預覽失敗:${e.message}"
            }
        }
    }

    private fun doLogin() {
        val pin = inputPin.text.toString().trim()
        if (pin.length != 4) {
            txtStatus.text = "請輸入 4 位 PIN"
            return
        }
        hideKeyboard()

        if (offlineMode) {
            doOfflineVerify(pin)
            return
        }

        val account = inputAccount.text.toString().trim()
        if (!deviceStore.isBound() && account.length != 8) {
            txtStatus.text = "請輸入 8 位店舖帳號(首次使用)"
            return
        }

        txtStatus.text = "連接伺服器…"
        btnLogin.isEnabled = false
        lifecycleScope.launch {
            val deviceId = withContext(Dispatchers.IO) { deviceStore.ensureDeviceId() }
            val result = withContext(Dispatchers.IO) {
                auth.fetchIdentities(deviceId, deviceStore.deviceSecret, account)
            }
            btnLogin.isEnabled = true

            when (result) {
                is IdentitiesResult.Ok -> {
                    result.payload.storeId?.let { deviceStore.storeId = it }
                    result.payload.storeName?.let { deviceStore.storeName = it }
                    val identities = result.payload.identities
                    if (identities.isEmpty()) {
                        txtStatus.text = "呢間店未有可用身份(店員未啟用?)"
                        return@launch
                    }
                    if (identities.size == 1) {
                        loginAs(identities.first(), pin)
                    } else {
                        // 身份卡選擇:對齊 web /login 嘅 identity cards
                        promptIdentity(identities, pin)
                    }
                }

                is IdentitiesResult.Fail -> when {
                    result.offline && offline.hasUsable() -> enterOfflineMode()
                    result.offline ->
                        txtStatus.text = "連唔到伺服器,而且本機冇離線憑證。首次使用必須聯網登入。"
                    else -> txtStatus.text =
                        "登入失敗:${result.error}${if (result.tableMissing) "(資料表未建立,請聯絡管理員)" else ""}"
                }
            }
        }
    }

    /** 多過一個身份 → AlertDialog 揀卡(原生 dialog,API 23 可用)。 */
    private fun promptIdentity(identities: List<LoginIdentity>, pin: String) {
        val labels = identities.map { iden ->
            val roleLabel = when (iden.role) {
                "owner" -> "店主"
                "manager" -> "店長"
                else -> "收銀"
            }
            "$roleLabel · ${iden.name}" + (iden.staffNo?.let { "($it)" } ?: "")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("選擇身份")
            .setItems(labels) { _, which -> loginAs(identities[which], pin) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 按身份 kind 分流登入端點(web 版同一套):owner → ledger/login;staff → pos/login/identity。 */
    private fun loginAs(identity: LoginIdentity, pin: String) {
        txtStatus.text = "登入中…"
        btnLogin.isEnabled = false
        lifecycleScope.launch {
            val resp = withContext(Dispatchers.IO) {
                runCatching {
                    if (identity.kind == "owner") {
                        // 帳號:綁定機上可留空(server 由裝置帶出店舖帳號)
                        val account = if (deviceStore.isBound()) "" else inputAccount.text.toString().trim()
                        auth.ownerLogin(account, pin, deviceStore.deviceId, deviceStore.deviceSecret)
                    } else {
                        auth.staffLogin(deviceStore.deviceId, deviceStore.deviceSecret, identity.staffNo ?: "", pin)
                    }
                }.getOrElse {
                    JSONObject()
                        .put("ok", false)
                        .put("error", "網絡錯誤:${it.message ?: "unknown"}")
                        .put("_httpStatus", 0)
                }
            }
            btnLogin.isEnabled = true
            handleLoginResponse(resp, identity, pin)
        }
    }

    private suspend fun handleLoginResponse(resp: JSONObject, identity: LoginIdentity, pin: String) {
        val ok = resp.optBoolean("ok", false)
        if (!ok) {
            val status = resp.optInt("_httpStatus", 0)
            if (status == 0) {
                if (offline.hasUsable()) {
                    enterOfflineMode()
                } else {
                    txtStatus.text = "連唔到伺服器:首次使用必須聯網登入"
                }
                return
            }
            txtStatus.text = "登入失敗:${resp.optString("error", "未知錯誤")}" +
                resp.optString("retryAfterSec", "").takeIf { it.isNotBlank() }?.let { "(等 ${it}s)" }.orEmpty()
            return
        }

        // ── 成功 ──
        // ① 裝置綁定(只有 owner 登入回應先有;secret 空白 = 已有授權,保留本機現有)
        val binding = resp.optJSONObject("deviceBinding")
        if (binding != null) {
            deviceStore.saveBinding(binding.optString("deviceId"), binding.optString("secret", ""))
        }

        // ② session 欄位映射:storeId ← session.merchantId;storeName ← session.name
        val session = resp.optJSONObject("session") ?: JSONObject()
        session.optString("merchantId", "").takeIf { it.isNotBlank() }?.let { deviceStore.storeId = it }
        session.optString("name", "").takeIf { it.isNotBlank() && deviceStore.storeName.isBlank() }
            ?.let { deviceStore.storeName = it }
        deviceStore.storeAccount = session.optString("account", deviceStore.storeAccount)
        session.optString("posDeviceToken", "").takeIf { it.isNotBlank() }
            ?.let { deviceStore.posDeviceToken = it }
        // Ledger 會話(線上訂單 RPC 需要)
        session.optString("ledgerAccessToken", "").takeIf { it.isNotBlank() }
            ?.let { deviceStore.ledgerAccessToken = it }
        session.optString("ledgerRefreshToken", "").takeIf { it.isNotBlank() }
            ?.let { deviceStore.ledgerRefreshToken = it }

        // ③ 本機重新派生驗證器(唔存 server 嘅 pinHash —— dump 走部機只影響呢部機一個身份)
        //    PBKDF2 120k 迭代 + 檔案寫入,搬去 IO thread,唔好卡 main
        withContext(Dispatchers.IO) { offline.save(identity, pin, session) }

        goHome(offline = false, name = identity.name)
    }

    // ---- 離線模式 ----

    private fun enterOfflineMode() {
        offlineMode = true
        txtOfflineBadge.visibility = View.VISIBLE
        val entries = offline.list().filter { !it.isExpired() }
        pendingIdentities = entries.map {
            LoginIdentity(kind = it.kind, id = it.id, name = it.name, staffNo = it.staffNo, role = it.role)
        }
        txtStatus.text = "連唔到伺服器 → 離線模式(請再打一次 PIN)"
        if (pendingIdentities.size > 1) {
            val labels = pendingIdentities.map { it.name }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("離線登入 · 選擇身份")
                .setItems(labels) { _, which ->
                    txtStatus.text = "離線登入:${pendingIdentities[which].name}"
                }
                .setNegativeButton("取消", null)
                .show()
        } else if (pendingIdentities.isNotEmpty()) {
            txtStatus.text = "離線登入:${pendingIdentities.first().name}"
        }
    }

    private fun doOfflineVerify(pin: String) {
        val entries = offline.list().filter { !it.isExpired() }
        if (entries.isEmpty()) {
            offlineMode = false
            txtOfflineBadge.visibility = View.GONE
            txtStatus.text = "本機離線憑證已過期,請聯網登入一次"
            return
        }
        // 多身份時用「最近選定/唯一」嗰個;離線 PIN 逐身份驗證(防店員攞店主權限)
        val chosen = entries.firstOrNull { it.name == txtStatus.text.toString().removePrefix("離線登入:") }
            ?: entries.first()
        val result = offline.verifyOffline(chosen.id, pin)
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

    private fun goHome(offline: Boolean, name: String) {
        startActivity(
            Intent(this, HomeActivity::class.java)
                .putExtra("operator", name)
                .putExtra("offline", offline)
        )
        finish()
    }

    private fun hideKeyboard() {
        runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        }
    }
}
