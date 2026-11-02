package com.macaupos.nativeapp

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.JsonStore

/**
 * 工作台選擇 —— 對齊 web `SelectWorkbenchScreen`(登入後第一頁):
 *
 * 堂食收銀台 / 快餐收銀台 / 後廚屏(已支援)· 零售/自助機/店員手機(灰住顯示,用 web)。
 * 選擇結果存本機(`workbench.json`),下次登入直接入返同一工作台;
 * 設置頁可以再返嚟呢頁改。
 */
class WorkbenchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workbench)

        val deviceStore = DeviceStore(this)
        val prefs = JsonStore(this, "workbench.json")

        findViewById<TextView>(R.id.txtStoreName).text =
            deviceStore.storeName.ifBlank { "門店" }
        findViewById<TextView>(R.id.txtMeta).text = listOfNotNull(
            deviceStore.storeAccount.takeIf { it.isNotBlank() }?.let { "店舖 $it" },
            "工作台:請選擇",
        ).joinToString(" · ")

        findViewById<TextView>(R.id.btnLogout).setOnClickListener {
            JsonStore(this, "offline-credential.json").clear()
            startActivity(Intent(this, MainActivity::class.java))
            finishAffinity()
        }

        findViewById<android.view.View>(R.id.cardDineIn).setOnClickListener {
            enter(prefs, "dine_in")
        }
        findViewById<android.view.View>(R.id.cardQuick).setOnClickListener {
            enter(prefs, "quick")
        }
        findViewById<android.view.View>(R.id.cardKitchen).setOnClickListener {
            prefs.write(org.json.JSONObject().put("mode", "kitchen"))
            startActivity(Intent(this, KdsActivity::class.java).putExtra("railActive", "kitchen"))
            finish()
        }
        findViewById<android.view.View>(R.id.cardUnsupported).setOnClickListener {
            Toast.makeText(this, "零售／自助機／店員手機版仲未移植,請用 web 版", Toast.LENGTH_LONG).show()
        }
    }

    private fun enter(prefs: JsonStore, mode: String) {
        prefs.write(org.json.JSONObject().put("mode", mode))
        val target = if (mode == "quick") OrderActivity::class.java else TablesActivity::class.java
        startActivity(Intent(this, target).putExtra("railActive", if (mode == "quick") "order" else "orders"))
        finish()
    }
}
