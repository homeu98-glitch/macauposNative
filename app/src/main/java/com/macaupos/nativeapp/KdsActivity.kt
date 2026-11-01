package com.macaupos.nativeapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.repo.KdsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 後廚屏(KDS) —— 對齊 web `/api/pos/kds/board` + `/kds/items`:
 *
 * - 訂單卡:單號 / 枱 / 送廚房時間;每行菜品顯示「完成/總數」
 * - **撳一行 = 標記整行完成**,再撳 = 取消(同 web 一樣以「整行」為單位)
 * - `degraded=kds_state_table_missing` → 頂部黃條警告(完成狀態唔會保存)
 */
class KdsActivity : ComponentActivity() {

    private lateinit var client: KdsClient
    private var orders: List<KdsClient.KdsOrder> = emptyList()
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kds)
        client = KdsClient(DeviceStore(this))

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { load() }

        adapter = Adapter()
        findViewById<RecyclerView>(R.id.recyclerKds).apply {
            layoutManager = LinearLayoutManager(this@KdsActivity)
            adapter = this@KdsActivity.adapter
        }
        load()
    }

    private fun load() {
        findViewById<TextView>(R.id.txtHint).text = "載入中…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { client.board() }
            when (r) {
                is KdsClient.Result.Ok -> {
                    orders = r.board.orders
                    val pending = orders.sumOf { o -> o.items.sumOf { it.quantity - it.doneQty } }
                    findViewById<TextView>(R.id.txtHint).text =
                        "${orders.size} 張單 · 未完成 $pending 項"
                    findViewById<TextView>(R.id.txtDegraded).visibility =
                        if (r.board.degraded) View.VISIBLE else View.GONE
                    adapter.notifyDataSetChanged()
                }
                is KdsClient.Result.Offline ->
                    findViewById<TextView>(R.id.txtHint).text = "離線:載入唔到後廚單"
                is KdsClient.Result.Fail ->
                    findViewById<TextView>(R.id.txtHint).text = "載入失敗:${r.error}"
            }
        }
    }

    private fun toggle(order: KdsClient.KdsOrder, item: KdsClient.KdsItem) {
        val target = if (item.doneQty >= item.quantity) 0 else item.quantity
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { client.setDone(order.id, item.itemKey, target) }
            r.onSuccess { load() }
                .onFailure { Toast.makeText(this@KdsActivity, "更新失敗:${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_kds_order, parent, false))

        override fun getItemCount(): Int = orders.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val order = orders[pos]
            h.txtOrderNo.text = order.orderNo.ifBlank { "#" + order.id.takeLast(6) }
            h.txtMeta.text = listOfNotNull(
                order.tableName?.let { "枱 $it" },
                order.sentAt?.substringAfter("T")?.take(5)?.let { "送單 $it" },
            ).joinToString(" · ")

            h.items.removeAllViews()
            order.items.forEach { item ->
                val row = LayoutInflater.from(this@KdsActivity).inflate(R.layout.item_kds_item, h.items, false)
                row.findViewById<TextView>(R.id.txtKdsItemName).text =
                    item.name + (item.note?.let { "($it)" } ?: "")
                val qty = row.findViewById<TextView>(R.id.txtKdsQty)
                val done = item.doneQty >= item.quantity
                qty.text = if (done) "✓ ${item.quantity}/${item.quantity}" else "${item.doneQty}/${item.quantity}"
                qty.setTextColor(getColor(if (done) R.color.ok_green else R.color.orange_600))
                row.alpha = if (done) 0.55f else 1f
                row.setOnClickListener { toggle(order, item) }
                h.items.addView(row)
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtOrderNo: TextView = v.findViewById(R.id.txtKdsOrderNo)
            val txtMeta: TextView = v.findViewById(R.id.txtKdsMeta)
            val items: LinearLayout = v.findViewById(R.id.kdsItems)
        }
    }
}
