package com.macaupos.nativeapp

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.JsonStore
import com.macaupos.nativeapp.data.db.AppDatabase
import com.macaupos.nativeapp.data.repo.PrinterStore
import com.macaupos.nativeapp.data.repo.SyncFlusher
import com.macaupos.nativeapp.print.model.PrinterCfgDto
import com.macaupos.nativeapp.print.net.TicketRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        findViewById<Button>(R.id.btnOrder).setOnClickListener {
            startActivity(Intent(this, OrderActivity::class.java))
        }
        findViewById<Button>(R.id.btnTables).setOnClickListener {
            startActivity(Intent(this, TablesActivity::class.java))
        }
        findViewById<Button>(R.id.btnSelfTest).setOnClickListener { runPrintSelfTest() }
        findViewById<Button>(R.id.btnShift).setOnClickListener {
            startActivity(Intent(this, ShiftActivity::class.java).putExtra("operator", operator))
        }
        findViewById<Button>(R.id.btnSoldOut).setOnClickListener {
            startActivity(Intent(this, SoldOutActivity::class.java))
        }
        findViewById<Button>(R.id.btnReport).setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java))
        }
        findViewById<Button>(R.id.btnMenuAdmin).setOnClickListener {
            startActivity(Intent(this, MenuAdminActivity::class.java))
        }
        findViewById<Button>(R.id.btnOnlineOrders).setOnClickListener {
            startActivity(Intent(this, OnlineOrdersActivity::class.java))
        }
        findViewById<Button>(R.id.btnDelivery).setOnClickListener {
            startActivity(Intent(this, DeliveryActivity::class.java))
        }
        findViewById<Button>(R.id.btnPrintCenter).setOnClickListener {
            startActivity(Intent(this, PrintCenterActivity::class.java))
        }
        findViewById<Button>(R.id.btnStaff).setOnClickListener {
            startActivity(Intent(this, StaffActivity::class.java))
        }
        findViewById<Button>(R.id.btnMember).setOnClickListener {
            startActivity(Intent(this, MemberLookupActivity::class.java))
        }
        findViewById<Button>(R.id.btnKds).setOnClickListener {
            startActivity(Intent(this, KdsActivity::class.java))
        }
        findViewById<Button>(R.id.btnPrintTemplates).setOnClickListener {
            startActivity(Intent(this, PrintTemplatesActivity::class.java))
        }
        findViewById<Button>(R.id.btnPrinter).setOnClickListener {
            startActivity(Intent(this, PrinterWizardActivity::class.java))
        }
        findViewById<Button>(R.id.btnSync).setOnClickListener { runSync() }
        findViewById<Button>(R.id.btnSyncHealth).setOnClickListener {
            startActivity(Intent(this, SyncHealthActivity::class.java))
        }
        findViewById<Button>(R.id.btnLogout).setOnClickListener { showLogoutDialog() }

        // 開頁即試 flush + 顯示 pending 狀態
        runSync(silent = true)
    }

    private fun showPrinterDialog() {
        val store = PrinterStore(this)
        val input = EditText(this).apply {
            hint = "打印機 IP(例 192.168.1.100)"
            setText(store.ip)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("打印機設置(LAN raw 9100)")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                store.ip = input.text.toString().trim()
                Toast.makeText(this, if (store.hasPrinter()) "已設定:${store.ip}:${store.port}" else "已清除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runSync(silent: Boolean = false) {
        val db = AppDatabase.get(this)
        val flusher = SyncFlusher(this, db)
        val txt = findViewById<TextView>(R.id.txtSyncState)
        lifecycleScope.launch {
            val pendingBefore = withContext(Dispatchers.IO) { db.syncOutboxDao().pendingCount() }
            val result = withContext(Dispatchers.IO) { flusher.flush() }
            val pendingAfter = withContext(Dispatchers.IO) { db.syncOutboxDao().pendingCount() }
            txt.text = when {
                pendingAfter == 0 -> "✓ 全部已同步"
                result.ok && result.sent > 0 -> "已送 ${result.sent} 筆 · 待同步 $pendingAfter"
                !result.ok && result.error?.startsWith("離線") == true -> "離線 · 待同步 $pendingAfter(恢復網絡自動補送)"
                !result.ok -> "同步失敗 · 待同步 $pendingAfter(${result.error})"
                else -> "待同步 $pendingAfter"
            }
            if (!silent) {
                Toast.makeText(this@HomeActivity, txt.text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("登出")
            .setMessage("會清空本機離線憑證,下次啟動要聯網登入。確定?")
            .setPositiveButton("登出") { _, _ ->
                JsonStore(this, "offline-credential.json").clear()
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
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
}
