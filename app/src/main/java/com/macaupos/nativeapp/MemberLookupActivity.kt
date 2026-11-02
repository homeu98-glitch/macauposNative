package com.macaupos.nativeapp

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.repo.LedgerOrdersClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * 會員查詢 —— 對齊 web Ledger RPC `merchant_lookup_customer_wallet(p_merchant_id, p_phone)`:
 * 回 `{registered, customer_phone, customer_id, display_name, balance_avos, gift_balance_avos}`。
 *
 * ⚠️ `balance_avos` 係「分」(1/100 MOP);顯示要除 100。
 * 未登記 → registered=false(唔可以當成「餘額 0」)。
 */
class MemberLookupActivity : ComponentActivity() {

    private lateinit var ledger: LedgerOrdersClient
    private lateinit var deviceStore: DeviceStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_member_lookup)
        RailNav.attach(this)
        deviceStore = DeviceStore(this)
        ledger = LedgerOrdersClient(deviceStore)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnLookup).setOnClickListener { lookup() }
    }

    private fun lookup() {
        val phone = findViewById<EditText>(R.id.inputPhone).text.toString().trim()
        if (phone.length < 6) {
            findViewById<TextView>(R.id.txtHint).text = "請輸入有效電話"
            return
        }
        findViewById<TextView>(R.id.txtHint).text = "查詢中…"
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                val body = JSONObject()
                    .put("p_merchant_id", deviceStore.storeId)
                    .put("p_phone", phone)
                ledger.rpcJson("merchant_lookup_customer_wallet", body)
            }
            findViewById<TextView>(R.id.txtHint).text = "用電話查會員餘額／禮金(Ledger)"
            if (res == null) {
                Toast.makeText(this@MemberLookupActivity, "離線:查唔到會員", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val (code, text) = res
            if (code !in 200..299) {
                Toast.makeText(this@MemberLookupActivity, "查詢失敗:HTTP $code", Toast.LENGTH_LONG).show()
                return@launch
            }
            val obj = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            val registered = obj.optBoolean("registered", false)
            findViewById<TextView>(R.id.txtMemberName).text =
                if (registered) obj.optString("display_name", "").ifBlank { "會員" } else "未登記會員"
            findViewById<TextView>(R.id.txtMemberMeta).text = listOfNotNull(
                obj.optString("customer_phone", phone).takeIf { it.isNotBlank() }?.let { "電話 $it" },
                obj.optString("customer_id", "").takeIf { it.isNotBlank() }?.let { "ID " + it.take(8) },
            ).joinToString(" · ")

            val balance = obj.optDouble("balance_avos", 0.0) / 100.0
            val gift = obj.optDouble("gift_balance_avos", 0.0) / 100.0
            findViewById<TextView>(R.id.txtBalance).text =
                if (registered) String.format(Locale.US, "餘額 MOP %.2f", balance) else "餘額 —"
            findViewById<TextView>(R.id.txtGift).text =
                if (registered) String.format(Locale.US, "其中禮金 MOP %.2f · 現金儲值 MOP %.2f", gift, (balance - gift).coerceAtLeast(0.0))
                else "此電話未登記會員"
        }
    }
}
