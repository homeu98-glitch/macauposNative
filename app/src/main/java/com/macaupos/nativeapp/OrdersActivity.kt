package com.macaupos.nativeapp

import android.content.Intent
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
import com.macaupos.nativeapp.data.db.AppDatabase
import com.macaupos.nativeapp.data.db.PosOrderEntity
import com.macaupos.nativeapp.data.repo.LedgerOrdersClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 訂單頁(/orders)—— 對齊 web `OrdersHub`:左=會員通線上訂單,右=店內線下訂單。
 *
 * 線上單經 Ledger `list_merchant_orders`(同 web 一條路);
 * 線下單讀本機 Room `pos_orders`(open + settled 最近 50 筆)。
 */
class OrdersActivity : ComponentActivity() {

    private lateinit var client: LedgerOrdersClient
    private lateinit var db: AppDatabase
    private lateinit var deviceStore: DeviceStore
    private var online: List<LedgerOrdersClient.OnlineOrder> = emptyList()
    private var local: List<PosOrderEntity> = emptyList()
    private lateinit var onlineAdapter: OnlineAdapter
    private lateinit var localAdapter: LocalAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_orders)
        RailNav.attach(this)

        client = LedgerOrdersClient(DeviceStore(this))
        db = AppDatabase.get(this)
        deviceStore = DeviceStore(this)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { load() }

        onlineAdapter = OnlineAdapter()
        findViewById<RecyclerView>(R.id.recyclerOnline).apply {
            layoutManager = LinearLayoutManager(this@OrdersActivity)
            adapter = onlineAdapter
        }
        localAdapter = LocalAdapter()
        findViewById<RecyclerView>(R.id.recyclerLocal).apply {
            layoutManager = LinearLayoutManager(this@OrdersActivity)
            adapter = localAdapter
        }
        load()
    }

    private fun load() {
        findViewById<TextView>(R.id.txtHint).text = "載入中…"
        lifecycleScope.launch {
            // 線上單(Ledger)
            val r = withContext(Dispatchers.IO) { client.list() }
            when (r) {
                is LedgerOrdersClient.Result.Ok -> {
                    online = r.orders.filter { it.status != "completed" && it.status != "cancelled" }
                    findViewById<TextView>(R.id.txtOnlineHint).text =
                        "待處理 ${online.count { it.status == "pending" }} · 共 ${online.size}"
                    onlineAdapter.notifyDataSetChanged()
                }
                is LedgerOrdersClient.Result.Offline ->
                    findViewById<TextView>(R.id.txtOnlineHint).text = "離線:載入唔到線上訂單"
                is LedgerOrdersClient.Result.Fail ->
                    findViewById<TextView>(R.id.txtOnlineHint).text = "載入失敗:${r.error}"
            }

            // 線下單(本機 Room)
            local = withContext(Dispatchers.IO) {
                db.posOrderDao().ordersSince(deviceStore.storeId, 0L).take(50)
            }
            findViewById<TextView>(R.id.txtLocalHint).text = "最近 ${local.size} 單"
            localAdapter.notifyDataSetChanged()

            val onlineCount = online.size
            findViewById<TextView>(R.id.txtHint).text = "線上 $onlineCount · 線下 ${local.size}"
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

    private fun localStatusLabel(status: String) = when (status) {
        "settled" -> "已結帳"
        "paid" -> "已結帳 / 待收尾"
        "reopened" -> "待重結"
        "sent_to_kitchen" -> "已下單"
        "draft" -> "未下單"
        else -> status
    }

    private fun actOnline(order: LedgerOrdersClient.OnlineOrder, action: String) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                when (action) {
                    "accept" -> client.accept(order.id)
                    "complete" -> client.updateStatus(order.id, "completed")
                    else -> client.markPaid(order.id)
                }
            }
            r.onSuccess {
                Toast.makeText(this@OrdersActivity, "✓ 已更新", Toast.LENGTH_SHORT).show()
                load()
            }.onFailure {
                Toast.makeText(this@OrdersActivity, "更新失敗:${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── 線上訂單 adapter ──
    private inner class OnlineAdapter : RecyclerView.Adapter<OnlineAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_online_order, parent, false))

        override fun getItemCount(): Int = online.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val o = online[pos]
            h.txtCode.text = o.pickupCode?.let { "取餐碼 $it" } ?: "#" + o.id.takeLast(6)
            h.txtStatus.text = statusLabel(o.status)
            h.txtMeta.text = buildList {
                o.customerName?.let { add(it) }
                o.phone?.let { add(it) }
                add(if (o.fulfillmentType == "delivery") "外送" else "自取")
                if (o.paymentStatus == "paid") add("已付款") else add("未付款")
            }.joinToString(" · ")
            h.txtItems.text = o.itemSummary ?: "—"
            h.txtTotal.text = String.format(Locale.US, "MOP %.2f", o.total)
            h.btnAccept.visibility = if (o.status == "pending") View.VISIBLE else View.GONE
            h.btnAccept.setOnClickListener { actOnline(o, "accept") }
            h.btnComplete.visibility = if (o.status != "pending") View.VISIBLE else View.GONE
            h.btnComplete.setOnClickListener { actOnline(o, "complete") }
            h.itemView.setOnLongClickListener {
                if (o.paymentStatus != "paid") { actOnline(o, "paid"); true }
                else { Toast.makeText(this@OrdersActivity, "已付款", Toast.LENGTH_SHORT).show(); true }
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

    // ── 線下訂單 adapter ──
    private inner class LocalAdapter : RecyclerView.Adapter<LocalAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_local_order, parent, false))

        override fun getItemCount(): Int = local.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val o = local[pos]
            h.txtNo.text = o.localOrderNo
            h.txtStatus.text = localStatusLabel(o.status)
            h.txtMeta.text = buildList {
                o.tableName?.let { add("枱 $it") }
                o.paymentMethod?.let { add(it) }
            }.joinToString(" · ")
            h.txtTotal.text = String.format(Locale.US, "MOP %.2f", o.total)
            h.btnOpen.setOnClickListener {
                startActivity(Intent(this@OrdersActivity, OrderDetailActivity::class.java).putExtra("orderId", o.id))
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtNo: TextView = v.findViewById(R.id.txtNo)
            val txtStatus: TextView = v.findViewById(R.id.txtStatus)
            val txtMeta: TextView = v.findViewById(R.id.txtMeta)
            val txtTotal: TextView = v.findViewById(R.id.txtTotal)
            val btnOpen: Button = v.findViewById(R.id.btnOpen)
        }
    }
}
