package com.macaupos.nativeapp

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.JsonStore
import com.macaupos.nativeapp.data.repo.PrinterStore

/**
 * 設置(/settings)—— 對齊 web `device-settings.tsx` 嘅 12 個 tab:
 *
 * 打印機 / 菜品打印設置 / 菜單 / 樓層與桌台 / 支付方式 / 線上接單 / 備註 /
 * 折扣 / 掃碼點餐 / 語言設置 / 版本 / 權限控制。
 *
 * 呢度做「常用 + 原生有能力」嘅 tab(打印機/語言/版本/菜單入口);
 * 其餘 tab 明確標示「完整設定請用 web 版」,唔假裝有。
 */
class SettingsActivity : ComponentActivity() {

    private val TABS = listOf(
        "device" to "打印機",
        "menu-print" to "菜品打印設置",
        "menu" to "菜單",
        "tables" to "樓層與桌台",
        "payments" to "支付方式",
        "online-orders" to "線上接單",
        "notes" to "備註",
        "discounts" to "折扣",
        "kiosk" to "掃碼點餐",
        "language" to "語言設置",
        "version" to "版本",
        "staff" to "權限控制",
    )
    private var activeTab = "device"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        RailNav.attach(this)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnBackToPos).setOnClickListener {
            // 返回收銀台:按 workbench mode 分流(同 rail 點餐)
            val mode = JsonStore(this, "workbench.json").read()?.optString("mode", "") ?: ""
            val target = if (mode == "quick") OrderActivity::class.java else TablesActivity::class.java
            startActivity(Intent(this, target).putExtra("railActive", "order"))
            finish()
        }

        renderTabs()
        renderTab()
    }

    private fun renderTabs() {
        val bar = findViewById<LinearLayout>(R.id.chipsTabs)
        bar.removeAllViews()
        TABS.forEach { (key, label) ->
            val selected = key == activeTab
            val tv = TextView(this).apply {
                text = label
                minHeight = dp(36)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(if (selected) R.color.card else R.color.slate_700))
                background = getDrawable(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
                setOnClickListener {
                    activeTab = key
                    renderTabs()
                    renderTab()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(6)
            bar.addView(tv, lp)
        }
    }

    private fun renderTab() {
        val panel = findViewById<LinearLayout>(R.id.panelContent)
        panel.removeAllViews()
        val status = findViewById<TextView>(R.id.txtStatus)

        when (activeTab) {
            "device" -> renderDevice(panel, status)
            "language" -> renderLanguage(panel, status)
            "version" -> renderVersion(panel, status)
            "menu" -> renderMenuEntry(panel, status)
            else -> renderPlaceholder(panel, status, activeTab)
        }
    }

    // ---- 打印機 ----

    private fun renderDevice(panel: LinearLayout, status: TextView) {
        val store = PrinterStore(this)
        status.text = "打印機綁定：設定 LAN 打印機 IP／藍牙／紙寬，收據同廚房單會送到呢部機。"

        fun field(label: String, value: String, hint: String): EditText {
            addLabel(panel, label)
            val e = EditText(this).apply {
                setText(value)
                setHint(hint)
                setSingleLine(true)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            panel.addView(e, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            return e
        }

        val ipField = field("打印機 IP", store.ip, "例 192.168.1.100")
        val portField = field("端口", store.port.toString(), "9100")
        val btField = field("藍牙 MAC（可選）", store.btAddress, "AA:BB:CC:DD:EE:FF")

        // 紙寬
        addLabel(panel, "紙寬")
        val paperRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var paperSize = store.paperSize
        listOf("58", "80").forEach { p ->
            val tv = TextView(this).apply {
                text = "${p}mm"
                minHeight = dp(36)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(6), dp(16), dp(6))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val on = paperSize == p
                setTextColor(getColor(if (on) R.color.card else R.color.slate_700))
                background = getDrawable(if (on) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
                setOnClickListener {
                    paperSize = p
                    store.paperSize = p
                    renderTab()
                }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(8)
            paperRow.addView(tv, lp)
        }
        panel.addView(paperRow)

        addButton(panel, "保存") {
            store.ip = ipField.text.toString().trim()
            store.port = portField.text.toString().trim().toIntOrNull() ?: 9100
            store.btAddress = btField.text.toString().trim()
            Toast.makeText(this, "已保存打印機設定", Toast.LENGTH_SHORT).show()
            status.text = "已保存。可以返收銀台測試打印。"
        }

        addButton(panel, "開啟打印機精靈（LAN 掃描／測試打印）") {
            startActivity(Intent(this, PrinterWizardActivity::class.java))
        }
    }

    // ---- 語言 ----

    private fun renderLanguage(panel: LinearLayout, status: TextView) {
        status.text = "介面語言（原生版目前固定繁體中文）"
        val cur = TextView(this).apply {
            text = "目前語言：繁體中文"
            textSize = 15f
            setTextColor(getColor(R.color.slate_900))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        panel.addView(cur)
    }

    // ---- 版本 ----

    private fun renderVersion(panel: LinearLayout, status: TextView) {
        status.text = "App 版本（Android 原生殼）"
        val v = TextView(this).apply {
            text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n包名 ${BuildConfig.APPLICATION_ID}"
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        panel.addView(v)
    }

    // ---- 菜單入口 ----

    private fun renderMenuEntry(panel: LinearLayout, status: TextView) {
        status.text = "菜單（菜品／分類／規格）管理入口"
        val t = TextView(this).apply {
            text = "菜品、分類、規格、價格管理喺「餐牌管理」頁進行。"
            textSize = 14f
            setTextColor(getColor(R.color.text_primary))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        panel.addView(t)
        addButton(panel, "開啟餐牌管理") {
            startActivity(Intent(this, MenuAdminActivity::class.java))
        }
    }

    // ---- 其餘佔位 ----

    private fun renderPlaceholder(panel: LinearLayout, status: TextView, key: String) {
        val label = TABS.firstOrNull { it.first == key }?.second ?: key
        status.text = "$label：完整設定請用 web 版（原生版未有此 tab 嘅完整功能）"
        val t = TextView(this).apply {
            text = "「$label」嘅完整設定功能喺網頁版收銀台（設置頁）提供。\n\n原生版已支援：打印機、菜單、沽清、交班、報表、庫存總覽等。"
            textSize = 14f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        panel.addView(t)
    }

    // ---- helper ----

    private fun addLabel(panel: LinearLayout, text: String) {
        panel.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.slate_900))
            setPadding(dp(4), dp(12), dp(4), dp(4))
        })
    }

    private fun addButton(panel: LinearLayout, text: String, onClick: () -> Unit) {
        val b = TextView(this).apply {
            this.text = text
            minHeight = dp(44)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.btn_text_on_accent))
            background = getDrawable(R.drawable.bg_btn_accent)
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        panel.addView(b, lp)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
