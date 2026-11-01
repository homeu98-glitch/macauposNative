package com.macaupos.nativeapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.repo.LedgerOrdersClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 線上訂單 —— 對齊 web「線上訂單面板」嘅核心:
 * 拉 Ledger `list_merchant_orders` → 列表 → 接單(`accept_order_in_store`)
 * → 完成(`update_order_status completed`);未付款可標記收款(`set_order_paid_in_store`)。
 *
 * 只用**已登入嘅 ledger 會話**(session.ledgerAccessToken),同 web 瀏覽器同一條路。
 */
class OnlineOrdersActivity : ComponentActivity() {

    private lateinit var client: LedgerOrdersClient
    private var orders: List<LedgerOrdersClient.OnlineOrder> = emptyList()
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_online_orders)
        client = LedgerOrdersClient(DeviceStore(this))

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { load() }

        adapter = Adapter()
        findViewById<RecyclerView>(R.id.recyclerOnline).apply {
            layoutManager = LinearLayoutManager(this@OnlineOrdersActivity)
            adapter = this@OnlineOrdersActivity.adapter
        }
        load()
    }

    private fun load() {
        findViewById<TextView>(R.id.txtHint).text = "載入中…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { client.list() }
            when (r) {
                is LedgerOrdersClient.Result.Ok -> {
                    // 只顯示未完成(completed/cancelled 唔喺待處理清單)
                    orders = r.orders.filter { it.status != "completed" && it.status != "cancelled" }
                    findViewById<TextView>(R.id.txtHint).text =
                        "待處理 ${orders.count { it.status == "pending" }} 單 · 共 ${orders.size} 單"
                    adapter.notifyDataSetChanged()
                }
                is LedgerOrdersClient.Result.Offline ->
                    findViewById<TextView>(R.id.txtHint).text = "離線:載入唔到線上訂單"
                is LedgerOrdersClient.Result.Fail ->
                    findViewById<TextView>(R.id.txtHint).text = "載入失敗:${r.error}"
            }
        }
    }

    private fun statusLabel(status: String) = when (status) {
        "pending" -> "待接單"
        "accepted" -> "已接單"
        "preparing" -> "製作中"
        "ready" -> "可取餐"
        "completed" -> "已完成"
        "cancelled" -> "已取消"
        else -> status
    }

    private fun act(order: LedgerOrdersClient.OnlineOrder, action: String) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                when (action) {
                    "accept" -> client.accept(order.id)
                    "complete" -> client.updateStatus(order.id, "completed")
                    else -> client.markPaid(order.id)
                }
            }
            r.onSuccess {
                Toast.makeText(this@OnlineOrdersActivity, "✓ 已更新", Toast.LENGTH_SHORT).show()
                load()
            }.onFailure {
                Toast.makeText(this@OnlineOrdersActivity, "更新失敗:${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_online_order, parent, false))

        override fun getItemCount(): Int = orders.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val o = orders[pos]
            h.txtCode.text = o.pickupCode?.let { "取餐碼 $it" } ?: "#" + o.id.takeLast(6)
            h.txtStatus.text = statusLabel(o.status)
            val meta = buildList {
                o.customerName?.let { add(it) }
                o.phone?.let { add(it) }
                add(if (o.fulfillmentType == "delivery") "外送" else "自取")
                if (o.paymentStatus == "paid") add("已付款") else add("未付款")
                o.deliveryAddress?.let { add(it) }
            }.joinToString(" · ")
            h.txtMeta.text = meta
            h.txtItems.text = o.itemSummary ?: "—"
            h.txtTotal.text = String.format(Locale.US, "MOP %.2f", o.total)

            h.btnAccept.visibility = if (o.status == "pending") View.VISIBLE else View.GONE
            h.btnAccept.setOnClickListener { act(o, "accept") }
            h.btnComplete.visibility = if (o.status != "pending") View.VISIBLE else View.GONE
            h.btnComplete.setOnClickListener { act(o, "complete") }

            // 長按 = 標記已收款(未付款先需要)
            h.itemView.setOnLongClickListener {
                if (o.paymentStatus != "paid") {
                    act(o, "paid"); true
                } else {
                    Toast.makeText(this@OnlineOrdersActivity, "已付款", Toast.LENGTH_SHORT).show(); true
                }
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtCode: TextView = v.findViewById(R.id.txtCode)
            val txtStatus: TextView = v.findViewById(R.id.txtStatus)
            val txtMeta: TextView = v.findViewById(R.id.txtMeta)
            val txtItems: TextView = v.findViewById(R.id.txtItems)
            val txtTotal: TextView = v.findViewById(R.id.txtTotal)
            val btnAccept: Button = v.findViewById(R.id.btnAccept)
            val btnComplete: Button = v.findViewById(R.id.btnComplete)
        }
    }
}
