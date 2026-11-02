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
    /** 目前工位(null = 全部);存本機,重開都記住 */
    private var station: String? = null
    private var stations: List<Triple<String, String, Int>> = emptyList()
    private lateinit var prefs: com.macaupos.nativeapp.data.JsonStore
    private var lastBoard: KdsClient.Board? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kds)
        RailNav.attach(this)
        client = KdsClient(DeviceStore(this))
        prefs = com.macaupos.nativeapp.data.JsonStore(this, "kds.json")
        station = prefs.read()?.optString("station", "")?.takeIf { it.isNotBlank() }

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
            val r = withContext(Dispatchers.IO) { client.board(station) }
            when (r) {
                is KdsClient.Result.Ok -> {
                    lastBoard = r.board
                    stations = r.board.stations
                    orders = r.board.orders
                    val pending = orders.sumOf { o -> o.items.sumOf { it.quantity - it.doneQty } }
                    findViewById<TextView>(R.id.txtHint).text =
                        "${orders.size} 張單 · 未完成 $pending 項"
                    findViewById<TextView>(R.id.txtDegraded).visibility =
                        if (r.board.degraded) View.VISIBLE else View.GONE
                    renderStationChips()
                    adapter.notifyDataSetChanged()
                }
                is KdsClient.Result.Offline ->
                    findViewById<TextView>(R.id.txtHint).text = "離線:載入唔到後廚單"
                is KdsClient.Result.Fail ->
                    findViewById<TextView>(R.id.txtHint).text = "載入失敗:${r.error}"
            }
        }
    }

    /** 工位 chips:全部 + 各打印分區(顯示待處理數);選擇存本機。 */
    private fun renderStationChips() {
        val bar = findViewById<LinearLayout>(R.id.chipsStation)
        bar.removeAllViews()
        val entries: List<Triple<String?, String, Int>> =
            listOf(Triple(null, "全部", orders.sumOf { o -> o.items.sumOf { it.quantity - it.doneQty } })) +
                stations.map { Triple(it.first as String?, it.second, it.third) }
        entries.forEach { (id, label, pending) ->
            val selected = id == station
            val tv = TextView(this).apply {
                text = if (pending > 0) "$label $pending" else label
                minHeight = 36
                gravity = android.view.Gravity.CENTER
                setPadding(28, 12, 28, 12)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(if (selected) R.color.card else R.color.slate_700))
                background = getDrawable(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
                setOnClickListener {
                    station = id
                    prefs.write(org.json.JSONObject().put("station", id ?: ""))
                    load()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = 8
            bar.addView(tv, lp)
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
