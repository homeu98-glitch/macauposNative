package com.macaupos.nativeapp

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.macaupos.nativeapp.data.repo.PrinterStore
import com.macaupos.nativeapp.print.model.PrintJobDto
import com.macaupos.nativeapp.print.model.PrinterCfgDto
import com.macaupos.nativeapp.print.net.BtPrinter
import com.macaupos.nativeapp.print.net.LanScanner
import com.macaupos.nativeapp.print.net.SdkPrinter
import com.macaupos.nativeapp.print.net.TicketRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 打印機設定精靈 —— 對齊 web「打印機」設定(簡化版):
 *
 * - 連線方式:LAN(raw socket,預設 9100)/ 藍牙(RFCOMM SPP)
 * - LAN:**自動掃描同網段**(LanScanner,/24 + 9100/631/80),亦可手動填 IP
 * - 藍牙:列出**已配對**裝置(未配對要先去系統藍牙配對 —— 同 web wizard 一致)
 * - 紙寬 58/80(影響每行格數)、測試打印(即場出紙驗證)
 */
class PrinterWizardActivity : ComponentActivity() {

    private lateinit var store: PrinterStore
    private lateinit var scanner: LanScanner
    private lateinit var bt: BtPrinter

    private var mode = "lan"
    private var paperSize = "58"
    private var selectedIp = ""
    private var selectedBt = ""
    private var scanning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_printer_wizard)

        store = PrinterStore(this)
        scanner = LanScanner(this)
        bt = BtPrinter(this)

        mode = store.connectionType
        paperSize = store.paperSize
        selectedIp = store.ip
        selectedBt = store.btAddress

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tabLan).setOnClickListener { setMode("lan") }
        findViewById<TextView>(R.id.tabBt).setOnClickListener { setMode("bluetooth") }
        findViewById<TextView>(R.id.chip58).setOnClickListener { setPaper("58") }
        findViewById<TextView>(R.id.chip80).setOnClickListener { setPaper("80") }
        findViewById<Button>(R.id.btnScan).setOnClickListener { scan() }
        findViewById<Button>(R.id.btnListBt).setOnClickListener { listBt() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testPrint() }
        findViewById<Button>(R.id.btnSavePrinter).setOnClickListener { save() }

        val ipInput = findViewById<EditText>(R.id.inputIp)
        ipInput.setText(store.ip)
        findViewById<EditText>(R.id.inputPort).setText(store.port.toString())

        setMode(mode)
        renderCurrent()
    }

    private fun setMode(next: String) {
        mode = next
        val onLan = next == "lan"
        findViewById<TextView>(R.id.tabLan).apply {
            setBackgroundResource(if (onLan) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
            setTextColor(getColor(if (onLan) R.color.card else R.color.slate_700))
        }
        findViewById<TextView>(R.id.tabBt).apply {
            setBackgroundResource(if (!onLan) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
            setTextColor(getColor(if (!onLan) R.color.card else R.color.slate_700))
        }
        findViewById<View>(R.id.sectionLan).visibility = if (onLan) View.VISIBLE else View.GONE
        findViewById<View>(R.id.sectionBt).visibility = if (onLan) View.GONE else View.VISIBLE
        if (!onLan) listBt()
    }

    private fun setPaper(size: String) {
        paperSize = size
        val is58 = size == "58"
        findViewById<TextView>(R.id.chip58).apply {
            setBackgroundResource(if (is58) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
            setTextColor(getColor(if (is58) R.color.card else R.color.slate_700))
        }
        findViewById<TextView>(R.id.chip80).apply {
            setBackgroundResource(if (!is58) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
            setTextColor(getColor(if (!is58) R.color.card else R.color.slate_700))
        }
    }

    private fun renderCurrent() {
        findViewById<TextView>(R.id.txtCurrent).text = when {
            mode == "bluetooth" && store.btAddress.isNotBlank() -> "藍牙 ${store.btAddress}"
            mode == "lan" && store.ip.isNotBlank() -> "LAN ${store.ip}:${store.port}"
            else -> "未設定打印機"
        }
    }

    // ---- LAN 掃描 ----

    private fun scan() {
        if (scanning) return
        val prefix = scanner.detectSubnetPrefix()
        if (prefix == null) {
            findViewById<TextView>(R.id.txtScanState).text = "偵測唔到本機 IP(請確認已連 Wi-Fi)"
            return
        }
        scanning = true
        findViewById<TextView>(R.id.txtScanState).text = "掃描中… $prefix.0/24"
        (findViewById<LinearLayout>(R.id.listLan)).removeAllViews()

        lifecycleScope.launch {
            val hits = withContext(Dispatchers.IO) {
                scanner.scanSubnet(prefix) { p ->
                    runOnUiThread {
                        findViewById<TextView>(R.id.txtScanState).text =
                            "掃描中… ${p.checked}/${p.total} · 已發現 ${p.found}"
                    }
                }
            }
            scanning = false
            findViewById<TextView>(R.id.txtScanState).text =
                if (hits.isEmpty()) "$prefix.0/24 冇發現打印機(可手動填 IP)"
                else "發現 ${hits.size} 部裝置 — 撳一下選用"

            val list = findViewById<LinearLayout>(R.id.listLan)
            hits.forEach { hit ->
                val row = TextView(this@PrinterWizardActivity).apply {
                    text = hit.ip + (hit.mac?.let { " · $it" } ?: "") +
                        " · 端口 " + hit.openPorts.joinToString("/")
                    setTextColor(getColor(R.color.slate_900))
                    textSize = 13f
                    setPadding(24, 26, 24, 26)
                    setBackgroundResource(R.drawable.bg_cart_row)
                    setOnClickListener {
                        selectedIp = hit.ip
                        findViewById<EditText>(R.id.inputIp).setText(hit.ip)
                        Toast.makeText(this@PrinterWizardActivity, "已選 ${hit.ip}", Toast.LENGTH_SHORT).show()
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 12
                list.addView(row, lp)
            }
        }
    }

    // ---- 藍牙 ----

    private fun listBt() {
        val list = findViewById<LinearLayout>(R.id.listBt)
        list.removeAllViews()
        if (!bt.isSupported()) {
            findViewById<TextView>(R.id.txtBtState).text = "呢部機冇藍牙"
            return
        }
        if (!bt.isEnabled()) {
            findViewById<TextView>(R.id.txtBtState).text = "藍牙未開(請去系統設定開啟)"
            return
        }
        val bonded = bt.enumerateBonded()
        findViewById<TextView>(R.id.txtBtState).text =
            if (bonded.isEmpty()) "冇已配對裝置 — 請先去系統藍牙配對打印機" else "已配對 ${bonded.size} 部"
        bonded.forEach { dev ->
            val row = TextView(this@PrinterWizardActivity).apply {
                text = (dev.name ?: "(未命名)") + "\n" + dev.address
                setTextColor(getColor(R.color.slate_900))
                textSize = 13f
                setPadding(24, 26, 24, 26)
                setBackgroundResource(R.drawable.bg_cart_row)
                setOnClickListener {
                    selectedBt = dev.address
                    Toast.makeText(this@PrinterWizardActivity, "已選 ${dev.address}", Toast.LENGTH_SHORT).show()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = 12
            list.addView(row, lp)
        }
    }

    // ---- 測試 / 儲存 ----

    private fun buildCfg(): PrinterCfgDto {
        val json = if (mode == "bluetooth") {
            JSONObject()
                .put("id", "wizard-bt")
                .put("connectionType", "bluetooth")
                .put("bluetoothAddress", selectedBt)
                .put("key", selectedBt)
                .put("paperSize", paperSize)
                .put("charset", "gb18030")
        } else {
            JSONObject()
                .put("id", "wizard-lan")
                .put("connectionType", "lan")
                .put("ipAddress", findViewById<EditText>(R.id.inputIp).text.toString().trim())
                .put("lanPort", findViewById<EditText>(R.id.inputPort).text.toString().trim().toIntOrNull() ?: 9100)
                .put("paperSize", paperSize)
                .put("charset", "gb18030")
        }
        return PrinterCfgDto.fromJson(json)
    }

    private fun testPrint() {
        val cfg = buildCfg()
        if (mode == "bluetooth" && selectedBt.isBlank()) {
            Toast.makeText(this, "先揀一部藍牙打印機", Toast.LENGTH_SHORT).show()
            return
        }
        if (mode == "lan" && cfg.ipAddress.isNullOrBlank()) {
            Toast.makeText(this, "先填或者掃描 IP", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "打印中…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val job = PrintJobDto.fromJson(JSONObject())
                    val bytes = TicketRenderer.renderTicket(job, cfg, "test", "Macau POS 測試頁", null, null)
                    SdkPrinter.printBytes(applicationContext, cfg, bytes).getOrThrow()
                }
            }
            Toast.makeText(
                this@PrinterWizardActivity,
                r.fold({ "✓ 測試頁已送出" }, { "打印失敗:${it.message}" }),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun save() {
        store.connectionType = mode
        store.paperSize = paperSize
        if (mode == "lan") {
            store.ip = findViewById<EditText>(R.id.inputIp).text.toString().trim()
            store.port = findViewById<EditText>(R.id.inputPort).text.toString().trim().toIntOrNull() ?: 9100
        } else {
            if (selectedBt.isBlank()) {
                Toast.makeText(this, "先揀一部藍牙打印機", Toast.LENGTH_SHORT).show()
                return
            }
            store.btAddress = selectedBt
        }
        renderCurrent()
        Toast.makeText(this, "✓ 已儲存打印機設定", Toast.LENGTH_SHORT).show()
        finish()
    }
}
