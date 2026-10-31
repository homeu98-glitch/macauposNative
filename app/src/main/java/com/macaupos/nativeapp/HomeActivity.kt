package com.macaupos.nativeapp

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.JsonStore
import com.macaupos.nativeapp.print.model.PrinterCfgDto
import com.macaupos.nativeapp.print.net.TicketRenderer
import org.json.JSONObject

/**
 * P0 主畫面骨架 —— 「登入 → 空主畫面,斷網都開得」。
 *
 * 打印引擎自檢:經 `TicketRenderer.renderTicket(kind="test")` 生成一頁 ESC/POS 測試字節,
 * 顯示字節數同頭 16 bytes hex —— 證明移植後嘅打印引擎喺新倉編譯可運行。
 * (P1 起接真打印機:`SdkPrinter.print` → LAN raw socket / 藍牙 RFCOMM / USB / 廠商 AAR。)
 */
class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val operator = intent.getStringExtra("operator") ?: ""
        val offline = intent.getBooleanExtra("offline", false)

        val deviceStore = DeviceStore(this)
        findViewById<TextView>(R.id.txtStoreName).text =
            deviceStore.storeName.ifBlank { getString(R.string.home_title) }
        findViewById<TextView>(R.id.txtOffline).text = if (offline) "離線" else "在線"
        findViewById<TextView>(R.id.txtOffline).setTextColor(
            if (offline) getColor(R.color.warn_red) else getColor(R.color.ok_green)
        )
        findViewById<TextView>(R.id.txtMeta).text =
            "v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) · 店員:$operator · " +
                "Android ${android.os.Build.VERSION.RELEASE}(API ${android.os.Build.VERSION.SDK_INT})"

        findViewById<TextView>(R.id.txtModules).text = MODULES

        findViewById<Button>(R.id.btnSelfTest).setOnClickListener { runPrintSelfTest() }
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            JsonStore(this, "offline-credential.json").clear()
            finish()
        }
    }

    private fun runPrintSelfTest() {
        val txt = findViewById<TextView>(R.id.txtSelfTest)
        txt.text = "生成中…"
        runCatching {
            val printer = PrinterCfgDto.fromJson(JSONObject().put("paperSize", "58").put("charset", "gb18030"))
            val job = com.macaupos.nativeapp.print.model.PrintJobDto.fromJson(JSONObject())
            val bytes = TicketRenderer.renderTicket(job, printer, "test", "Macau POS 自檢", null, null)
            val head = bytes.take(16).joinToString(" ") { String.format("%02X", it) }
            "✓ ESC/POS 測試頁字節生成成功\n${bytes.size} bytes · 頭 16 bytes:$head\n" +
                "(P1 接真打印機:LAN 9100 / 藍牙 RFCOMM / USB / Sunmi AAR)"
        }.onSuccess { txt.text = it }
            .onFailure { txt.text = "✗ 自檢失敗:${it.message}" }
    }

    private companion object {
        val MODULES = """
            ☐ 落單/餐牌(P1)
            ☐ 桌台(P1)
            ☐ 打印:收據/廚房單(P1 · 引擎已就位)
            ☐ 離線同步 outbox/flush(P1)
            ☐ 交班(P2)
            ☐ 沽清/線上訂單(KDS)(P2)
            ☐ 外送 board(P2)
            ☐ 餐牌管理(P3)
            ☐ 打印模板/打印中心(P3)
            ☐ 員工/會員(P3)
            ☐ 報表/庫存(P4)
        """.trimIndent()
    }
}
