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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.db.AppDatabase
import com.macaupos.nativeapp.data.db.PosOrderEntity
import com.macaupos.nativeapp.data.repo.BootstrapRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 桌台總覽 —— 1:1 對照 web `pos-app.tsx` 桌台模式:
 *
 * - 桌卡:空閒 = 白底;有單 = **實底橙** + 白字(web cardTone)
 * - 右上白色「訂單號」角標(有單先出)
 * - 內容:枱名 → 區域 → 應收 → 狀態膠囊(bg-white/25 白字 / bg-orange-50 橙字)
 * - 網格:3 → 4 欄(平板 4 欄,對齊 web md:grid-cols-4)
 */
class TablesActivity : ComponentActivity() {

    data class TableLite(val id: String, val name: String, val area: String?)

    private lateinit var db: AppDatabase
    private lateinit var bootstrapRepo: BootstrapRepository
    private lateinit var deviceStore: DeviceStore

    private var tables: List<TableLite> = emptyList()
    private var openByTable: Map<String, PosOrderEntity> = emptyMap()

    private lateinit var adapter: TableAdapter
    private lateinit var txtSyncHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tables)

        db = AppDatabase.get(this)
        bootstrapRepo = BootstrapRepository(applicationContext, db)
        deviceStore = DeviceStore(this)
        txtSyncHint = findViewById(R.id.txtSyncHint)

        adapter = TableAdapter()
        findViewById<RecyclerView>(R.id.recyclerTables).apply {
            layoutManager = GridLayoutManager(this@TablesActivity, 4)
            adapter = this@TablesActivity.adapter
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            lifecycleScope.launch {
                bootstrapRepo.refresh(deviceStore.storeId)
                reload()
                Toast.makeText(this@TablesActivity, "已刷新", Toast.LENGTH_SHORT).show()
            }
        }

        txtSyncHint.text = "點開桌子後進入點餐介面。桌台狀態:空閒 / 已下單"
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val cached = bootstrapRepo.cached(deviceStore.storeId)
            if (cached == null) {
                val r = bootstrapRepo.refresh(deviceStore.storeId)
                if (r.isFailure) {
                    txtSyncHint.text = "餐牌未載入:${r.exceptionOrNull()?.message}"
                    return@launch
                }
                reload()
                return@launch
            }
            bootstrapRepo.cached(deviceStore.storeId)?.let { bs ->
                tables = bs.tables.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
                        val id = o.optString("id", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val name = o.optString("name", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        TableLite(id, name, o.optString("area", "").takeIf { it.isNotBlank() })
                    }
                }
            }
            openByTable = withContext(Dispatchers.IO) {
                db.posOrderDao().openOrders(deviceStore.storeId)
                    .filter { !it.tableName.isNullOrBlank() }
                    .associateBy { it.tableName!! }
            }
            txtSyncHint.text = "點開桌子後進入點餐介面 · 開放中 ${openByTable.size} 張 · 全部 ${tables.size} 張枱"
            adapter.notifyDataSetChanged()
        }
    }

    private fun openTable(table: TableLite) {
        val open = openByTable[table.name]
        if (open != null) {
            startActivity(Intent(this, OrderDetailActivity::class.java).putExtra("orderId", open.id))
        } else {
            startActivity(
                Intent(this, OrderActivity::class.java)
                    .putExtra("tableName", table.name)
                    .putExtra("newForTable", true)
            )
        }
    }

    private fun statusLabel(status: String): String = when (status) {
        "settled" -> "已結帳"
        "paid" -> "已結帳 / 待收尾"
        "reopened" -> "待重結"
        "sent_to_kitchen" -> "已下單"
        "draft" -> "未下單"
        else -> "空閒"
    }

    private inner class TableAdapter : RecyclerView.Adapter<TableAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_table, parent, false))

        override fun getItemCount(): Int = tables.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val t = tables[pos]
            val open = openByTable[t.name]
            h.txtTableName.text = t.name
            if (open != null) {
                h.itemView.setBackgroundResource(R.drawable.bg_table_occupied)
                h.txtTableName.setTextColor(getColor(R.color.card))
                h.txtArea.text = t.area ?: ""
                h.txtArea.setTextColor(getColor(R.color.white_85))
                h.txtOrderBadge.visibility = View.VISIBLE
                h.txtOrderBadge.text = open.localOrderNo
                h.txtDue.visibility = View.VISIBLE
                h.txtDue.text = String.format(Locale.US, "應收 MOP%.2f", open.total)
                h.txtState.setBackgroundResource(R.drawable.bg_pill_on_solid)
                h.txtState.setTextColor(getColor(R.color.card))
                h.txtState.text = statusLabel(open.status)
            } else {
                h.itemView.setBackgroundResource(R.drawable.bg_table_free)
                h.txtTableName.setTextColor(getColor(R.color.slate_900))
                h.txtArea.text = t.area ?: ""
                h.txtArea.setTextColor(getColor(R.color.text_secondary))
                h.txtOrderBadge.visibility = View.GONE
                h.txtDue.visibility = View.GONE
                h.txtState.setBackgroundResource(R.drawable.bg_pill_light)
                h.txtState.setTextColor(getColor(R.color.orange_600))
                h.txtState.text = "空閒"
            }
            h.itemView.setOnClickListener { openTable(t) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtTableName: TextView = v.findViewById(R.id.txtTableName)
            val txtOrderBadge: TextView = v.findViewById(R.id.txtOrderBadge)
            val txtArea: TextView = v.findViewById(R.id.txtArea)
            val txtDue: TextView = v.findViewById(R.id.txtDue)
            val txtState: TextView = v.findViewById(R.id.txtState)
        }
    }
}
