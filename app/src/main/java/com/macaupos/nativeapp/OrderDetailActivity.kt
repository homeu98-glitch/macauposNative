package com.macaupos.nativeapp

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.macaupos.nativeapp.data.db.AppDatabase
import com.macaupos.nativeapp.data.repo.OrderRepository
import com.macaupos.nativeapp.data.repo.PrintService
import com.macaupos.nativeapp.data.repo.SyncFlusher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P1-B 訂單詳情:睇單 → 加菜 / 結帳 / 補打收據。
 *
 * 結帳:揀付款方式(AlertDialog 單選,預設清單;付款方式配置 P3 對齊 web 設置頁)
 * → settleOrder(本地 Room settled + ORDER_SETTLED outbox,payload 補帶完整 items)
 * → 即打收據(kind="receipt")→ flush。
 */
class OrderDetailActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private lateinit var orderRepo: OrderRepository
    private lateinit var printService: PrintService
    private lateinit var syncFlusher: SyncFlusher

    private var order: com.macaupos.nativeapp.data.db.PosOrderEntity? = null

    // 預設付款方式(P3 接 web 設置頁配置;現金排第一 = web fallback 同款)
    private val paymentMethods = listOf("現金", "微信", "支付寶", "現金券", "其他")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_detail)

        db = AppDatabase.get(this)
        orderRepo = OrderRepository(this, db)
        printService = PrintService(this)
        syncFlusher = SyncFlusher(this, db)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAddItems).setOnClickListener {
            order?.let {
                startActivity(
                    Intent(this, OrderActivity::class.java)
                        .putExtra("existingOrderId", it.id)
                        .putExtra("tableName", it.tableName ?: "")
                )
                finish()
            }
        }
        findViewById<Button>(R.id.btnSettle).setOnClickListener { showSettleDialog() }
        findViewById<Button>(R.id.btnReprint).setOnClickListener { reprintReceipt() }

        loadOrder()
    }

    override fun onResume() {
        super.onResume()
        loadOrder()
    }

    private fun loadOrder() {
        val id = intent.getStringExtra("orderId") ?: return
        lifecycleScope.launch {
            order = withContext(Dispatchers.IO) { orderRepo.byId(id) }
            render()
        }
    }

    private fun render() {
        val o = order ?: return
        val currency = "MOP"
        findViewById<TextView>(R.id.txtOrderNo).text = o.localOrderNo
        val statusLabel = when (o.status) {
            "settled" -> "已結帳" + o.paymentMethod?.let { " · $it" }.orEmpty()
            "paid" -> "已收款(待收尾)"
            else -> "開放中"
        }
        findViewById<TextView>(R.id.txtMeta).text = listOfNotNull(
            o.tableName?.let { "枱 $it" },
            statusLabel,
            SimpleDateFormat("HH:mm", Locale.US).format(Date(o.createdAt)),
            if (o.syncState == "pending") "⏳ 待同步" else null,
        ).joinToString(" · ")

        val items = JSONArray(o.items)
        val sb = StringBuilder()
        var subtotal = 0.0
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val qty = it.optInt("quantity", 1)
            val price = it.optDouble("price", 0.0)
            subtotal += price * qty
            sb.append(String.format(Locale.US, "%s ×%-3d %s%8.2f", it.optString("name", "?"), qty, currency, price * qty))
            it.optString("note", "").takeIf { n -> n.isNotBlank() }?.let { n -> sb.append("\n    備註:$n") }
            sb.append("\n")
        }
        findViewById<TextView>(R.id.txtItems).text = sb.toString()
        findViewById<TextView>(R.id.txtTotal).text = String.format(
            Locale.US, "總計 %s%.2f%s", currency, o.total,
            o.settledAt?.let { "\n已收 ${String.format(Locale.US, "%.2f", o.receivedAmount ?: o.total)} · 找零 ${String.format(Locale.US, "%.2f", o.changeDue ?: 0.0)}" } ?: "",
        )
        findViewById<Button>(R.id.btnSettle).visibility =
            if (o.status == "settled") View.GONE else View.VISIBLE
    }

    private fun showSettleDialog() {
        val o = order ?: return
        AlertDialog.Builder(this)
            .setTitle("結帳 ${o.localOrderNo} · MOP${String.format(Locale.US, "%.2f", o.total)}")
            .setItems(paymentMethods.toTypedArray()) { _, which ->
                settle(paymentMethods[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun settle(method: String) {
        val o = order ?: return
        lifecycleScope.launch {
            val result = orderRepo.settleOrder(o, method, receivedAmount = o.total)
            result.onSuccess { settled ->
                // 即打收據 + flush
                val printResult = withContext(Dispatchers.IO) {
                    printService.printReceipt(orderRepo.orderRowJson(settled), method, settled.total)
                }
                withContext(Dispatchers.IO) { syncFlusher.flush() }
                withContext(Dispatchers.Main) {
                    loadOrder()
                    Toast.makeText(
                        this@OrderDetailActivity,
                        "✓ 已結帳 $method" + printResult.fold({ " · 收據已打印" }, { " · ⚠ 收據:${it.message}" }),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure {
                Toast.makeText(this@OrderDetailActivity, "結帳失敗:${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun reprintReceipt() {
        val o = order ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                printService.printReceipt(orderRepo.orderRowJson(o), o.paymentMethod ?: "現金", o.total)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@OrderDetailActivity,
                    result.fold({ "收據已補打" }, { "補打失敗:${it.message}" }),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
