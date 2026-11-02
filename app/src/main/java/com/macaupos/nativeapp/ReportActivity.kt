package com.macaupos.nativeapp

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.db.AppDatabase
import com.macaupos.nativeapp.data.db.PosOrderEntity
import com.macaupos.nativeapp.domain.ReportStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 日報表 —— **離線可用**:直接由本機 Room `pos_orders` 聚合(唔靠 server)。
 *
 * 口徑見 `ReportStats`(只計已結帳、客單價、支付方式、菜品排行),
 * 對齊 web 報表:營業額 = Σ total、支付方式空白當現金。
 */
class ReportActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private lateinit var deviceStore: DeviceStore

    /** 目前顯示嘅日期(當日 00:00)。 */
    private var dayStart: Long = startOfToday()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)
        RailNav.attach(this)
        db = AppDatabase.get(this)
        deviceStore = DeviceStore(this)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnPrevDay).setOnClickListener { shiftDay(-1) }
        findViewById<TextView>(R.id.btnNextDay).setOnClickListener { shiftDay(+1) }
        load()
    }

    private fun shiftDay(delta: Int) {
        val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
        cal.add(Calendar.DAY_OF_MONTH, delta)
        // 唔准睇未來
        val next = cal.timeInMillis
        if (next > startOfToday()) return
        dayStart = next
        load()
    }

    private fun load() {
        val dayEnd = dayStart + 24L * 60 * 60 * 1000
        findViewById<TextView>(R.id.txtDate).text =
            SimpleDateFormat("yyyy-MM-dd(E)", Locale.TRADITIONAL_CHINESE).format(Date(dayStart))
        lifecycleScope.launch {
            val orders = withContext(Dispatchers.IO) {
                db.posOrderDao().ordersBetween(deviceStore.storeId, dayStart, dayEnd)
            }
            render(orders)
        }
    }

    private fun render(orders: List<PosOrderEntity>) {
        val inputs = orders.map { o ->
            ReportStats.OrderInput(
                total = o.total,
                paymentMethod = o.paymentMethod,
                status = o.status,
                lines = parseLines(o.items),
            )
        }
        val stats = ReportStats.compute(inputs)
        val currency = "MOP"
        fun money(v: Double) = String.format(Locale.US, "%s %.2f", currency, v)

        // 應收 = subtotal 加總(未扣優惠前嘅原價);實收 = total 加總;淨營業額 = 實收 − 退款
        val receivable = orders.filter { it.status == "settled" }.sumOf { it.subtotal }
        val refundTotal = orders.filter { it.status == "refunded" || it.status == "partially_refunded" }.sumOf { it.total }
        val net = stats.revenue - refundTotal

        findViewById<TextView>(R.id.txtReceivable).text = money(receivable)
        findViewById<TextView>(R.id.txtRevenue).text = money(stats.revenue)
        findViewById<TextView>(R.id.txtNet).text = money(net)
        findViewById<TextView>(R.id.txtRefund).text =
            if (refundTotal > 0) "實收 − 退款 ${money(refundTotal)}" else "實收 − 退款（本期冇退款）"
        findViewById<TextView>(R.id.txtOrders).text = stats.orderCount.toString()
        findViewById<TextView>(R.id.txtAvg).text = money(stats.avgTicket)

        // 支付方式分項表格(應收/實收/單數)
        val box = findViewById<android.widget.LinearLayout>(R.id.boxPaymentRows)
        box.removeAllViews()
        val byMethod = LinkedHashMap<String, Triple<Double, Double, Int>>()
        orders.filter { it.status == "settled" }.forEach { o ->
            val m = o.paymentMethod?.ifBlank { "現金" } ?: "現金"
            val cur = byMethod[m] ?: Triple(0.0, 0.0, 0)
            byMethod[m] = Triple(cur.first + o.subtotal, cur.second + o.total, cur.third + 1)
        }
        if (byMethod.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "未有已結帳單"
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(8))
            })
        } else {
            byMethod.entries.sortedByDescending { it.value.second }.forEach { (m, v) ->
                box.addView(paymentRow(m, money(v.first), money(v.second), v.third.toString()))
            }
        }

        findViewById<TextView>(R.id.txtTopItems).text =
            if (stats.topItems.isEmpty()) "未有已結帳單"
            else stats.topItems.mapIndexed { i, it ->
                String.format(Locale.US, "%d. %s ×%d · %s", i + 1, it.name, it.quantity, money(it.amount))
            }.joinToString("\n")
    }

    private fun paymentRow(method: String, receivable: String, received: String, count: String): android.view.View {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        fun cell(text: String, weight: Float, end: Boolean = false): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(getColor(R.color.text_primary))
                if (end) gravity = android.view.Gravity.END
            }
        }
        row.addView(cell(method, 1.2f), android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
        row.addView(cell(receivable, 1f, true), android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(cell(received, 1f, true), android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(cell(count, 0.6f, true), android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f))
        return row
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun parseLines(itemsJson: String): List<ReportStats.OrderLine> {
        val arr = runCatching { JSONArray(itemsJson) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ReportStats.OrderLine>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").takeIf { it.isNotBlank() } ?: continue
            val qty = o.optInt("quantity", 1)
            val price = o.optDouble("price", 0.0)
            out.add(ReportStats.OrderLine(name, qty, price * qty))
        }
        return out
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
