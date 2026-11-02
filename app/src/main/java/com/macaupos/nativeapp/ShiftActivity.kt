package com.macaupos.nativeapp

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.db.AppDatabase
import com.macaupos.nativeapp.data.repo.ShiftClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 交班 —— 對齊 web `/api/pos/shift`:
 *
 * - GET active → 顯示目前班次(店員/開工時間/已開工幾耐)
 * - POST action=open  → 開工(已有 active 回 conflict:true,以現有為準)
 * - POST action=close → 結班(帶 closingNote / actualCash / cashDifference / summary)
 *
 * 今日匯總由**本機 Room** 聚合(離線都睇得到),結班時一齊上傳 ——
 * 同 web `ShiftHistoryRecord.summary` 同一份口徑(營業額/單數/支付方式)。
 */
class ShiftActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private lateinit var deviceStore: DeviceStore
    private lateinit var client: ShiftClient

    private var active: ShiftClient.Shift? = null
    private var revenue = 0.0
    private var settledCount = 0
    private var breakdown: Map<String, Double> = emptyMap()
    private var operatorName = "店員"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shift)
        RailNav.attach(this)

        db = AppDatabase.get(this)
        deviceStore = DeviceStore(this)
        client = ShiftClient(deviceStore)
        operatorName = intent.getStringExtra("operator")?.takeIf { it.isNotBlank() }
            ?: deviceStore.storeAccount.ifBlank { "店員" }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnOpenShift).setOnClickListener { openShift() }
        findViewById<Button>(R.id.btnCloseShift).setOnClickListener { closeShift() }

        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) { localSummary() }
            revenue = summary.first
            settledCount = summary.second
            breakdown = summary.third

            val result = withContext(Dispatchers.IO) { client.activeShift() }
            when (result) {
                is ShiftClient.Result.Ok -> {
                    active = result.active
                    render(result.active)
                }
                is ShiftClient.Result.Offline -> {
                    render(active) // 保留上次已知;離線唔可以亂開新班
                    findViewById<TextView>(R.id.txtShiftSub).text = "離線:睇唔到最新班次狀態,恢復網絡再試"
                }
                is ShiftClient.Result.Fail -> {
                    render(active)
                    findViewById<TextView>(R.id.txtShiftSub).text = "讀取班次失敗:${result.error}"
                }
            }
        }
    }

    private fun render(shift: ShiftClient.Shift?) {
        val currency = "MOP"
        findViewById<TextView>(R.id.txtRevenue).text = String.format(Locale.US, "%s %.2f", currency, revenue)
        findViewById<TextView>(R.id.txtOrderCount).text = settledCount.toString()

        val sb = StringBuilder()
        if (breakdown.isEmpty()) {
            sb.append("今日未有已結帳單")
        } else {
            breakdown.entries.sortedByDescending { it.value }.forEach { (method, amount) ->
                sb.append("$method: ").append(String.format(Locale.US, "%s %.2f", currency, amount)).append("\n")
            }
        }
        findViewById<TextView>(R.id.txtBreakdown).text = sb.toString().trimEnd()

        val txtState = findViewById<TextView>(R.id.txtShiftState)
        val txtMeta = findViewById<TextView>(R.id.txtShiftMeta)
        val txtSub = findViewById<TextView>(R.id.txtShiftSub)
        val btnOpen = findViewById<Button>(R.id.btnOpenShift)
        val btnClose = findViewById<Button>(R.id.btnCloseShift)

        if (shift == null) {
            txtState.text = "未開工"
            txtMeta.text = "今日未開工"
            txtSub.text = "未開工前唔可以開枱／落單(同 web 一致)"
            btnOpen.visibility = View.VISIBLE
            btnClose.visibility = View.GONE
        } else {
            val openedAt = parseIso(shift.openedAt)
            val hours = ((System.currentTimeMillis() - openedAt) / 3_600_000.0)
            txtState.text = "已開工"
            txtMeta.text = "${shift.employeeName.ifBlank { shift.employeeAccount }} · " +
                SimpleDateFormat("HH:mm", Locale.US).format(Date(openedAt)) + " 開工"
            txtSub.text = String.format(Locale.US, "已開工 %.1f 小時 · 班次 %s", hours, shift.id.take(14))
            btnOpen.visibility = View.GONE
            btnClose.visibility = View.VISIBLE
        }
    }

    private fun openShift() {
        val input = EditText(this).apply { hint = "開工備註(可選)" }
        AlertDialog.Builder(this)
            .setTitle("開工")
            .setMessage("開始今日班次後就可以開枱／落單。")
            .setView(input)
            .setPositiveButton("開工") { _, _ ->
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) {
                        client.open(deviceStore.storeAccount, operatorName, input.text.toString().trim())
                    }
                    when (r) {
                        is ShiftClient.Result.Ok -> {
                            active = r.active
                            render(r.active)
                            Toast.makeText(
                                this@ShiftActivity,
                                if (r.conflict) "已有進行中班次,以現有為準" else "✓ 已開工",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is ShiftClient.Result.Offline ->
                            Toast.makeText(this@ShiftActivity, "離線:開工需要聯網(同 web 一致)", Toast.LENGTH_LONG).show()
                        is ShiftClient.Result.Fail ->
                            Toast.makeText(this@ShiftActivity, "開工失敗:${r.error}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun closeShift() {
        val cash = EditText(this).apply {
            hint = "實收現金(可選)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val note = EditText(this).apply { hint = "結班備註(可選)" }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(cash)
            addView(note)
        }
        AlertDialog.Builder(this)
            .setTitle(
                String.format(Locale.US, "結班 · 營業額 MOP %.2f · %d 單", revenue, settledCount)
            )
            .setView(box)
            .setPositiveButton("確認結班") { _, _ ->
                val cashValue = cash.text.toString().trim().toDoubleOrNull()
                val diff = cashValue?.let { it - revenue }
                lifecycleScope.launch {
                    val summary = JSONObject()
                        .put("revenue", revenue)
                        .put("orderCount", settledCount)
                        .put("breakdown", JSONObject(breakdown as Map<*, *>))
                        .put("closedLocallyAt", System.currentTimeMillis())
                    val r = withContext(Dispatchers.IO) {
                        client.close(note.text.toString().trim(), cashValue, diff, summary)
                    }
                    when (r) {
                        is ShiftClient.Result.Ok -> {
                            active = null
                            render(null)
                            Toast.makeText(this@ShiftActivity, "✓ 已結班", Toast.LENGTH_SHORT).show()
                        }
                        is ShiftClient.Result.Offline ->
                            Toast.makeText(this@ShiftActivity, "離線:結班需要聯網(同 web 一致)", Toast.LENGTH_LONG).show()
                        is ShiftClient.Result.Fail ->
                            Toast.makeText(this@ShiftActivity, "結班失敗:${r.error}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 今日已結帳單的營業額 / 單數 / 支付方式分佈(本機 Room)。 */
    private suspend fun localSummary(): Triple<Double, Int, Map<String, Double>> {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val orders = db.posOrderDao().ordersSince(deviceStore.storeId, start)
            .filter { it.status == "settled" }
        val revenue = orders.sumOf { it.total }
        val byMethod = LinkedHashMap<String, Double>()
        orders.forEach { o ->
            val method = o.paymentMethod?.ifBlank { "現金" } ?: "現金"
            byMethod[method] = (byMethod[method] ?: 0.0) + o.total
        }
        return Triple(revenue, orders.size, byMethod)
    }

    private fun parseIso(iso: String): Long = runCatching {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val clean = iso.replace("Z", "").substringBefore(".")
        val base = fmt.parse(clean)?.time ?: System.currentTimeMillis()
        // 帶時區偏移(+08:00)時補返
        val offset = Regex("([+-])(\\d{2}):(\\d{2})$").find(iso)
        if (offset != null) {
            val sign = if (offset.groupValues[1] == "-") -1 else 1
            val h = offset.groupValues[2].toInt()
            val m = offset.groupValues[3].toInt()
            base - sign * (h * 60 + m) * 60_000L
        } else base
    }.getOrDefault(System.currentTimeMillis())
}
