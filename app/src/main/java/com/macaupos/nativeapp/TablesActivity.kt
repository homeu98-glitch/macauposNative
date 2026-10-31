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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P1-B 桌台總覽 —— 桌源 = bootstrap `tables`(StoreTable:{id,name,area});
 * 每枱狀態由本地 Room 未結帳單判斷(sent_to_kitchen/paid/reopened = 有單)。
 * 點枱:有單 → 訂單詳情(加菜/結帳);冇單 → 新單(OrderActivity 預填枱號)。
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
        bootstrapRepo = BootstrapRepository(db)
        deviceStore = DeviceStore(this)
        txtSyncHint = findViewById(R.id.txtSyncHint)

        adapter = TableAdapter()
        val recycler = findViewById<RecyclerView>(R.id.recyclerTables)
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter

        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            lifecycleScope.launch {
                bootstrapRepo.refresh(deviceStore.storeId)
                reload()
                Toast.makeText(this@TablesActivity, "已刷新", Toast.LENGTH_SHORT).show()
            }
        }

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
            txtSyncHint.text = String.format(
                Locale.US, "開放中 %d 張 · %s",
                openByTable.size,
                SimpleDateFormat("HH:mm", Locale.US).format(Date()),
            )
            adapter.notifyDataSetChanged()
        }
    }

    private fun openTable(table: TableLite) {
        val open = openByTable[table.name]
        if (open != null) {
            startActivity(
                Intent(this, OrderDetailActivity::class.java).putExtra("orderId", open.id)
            )
        } else {
            startActivity(
                Intent(this, OrderActivity::class.java)
                    .putExtra("tableName", table.name)
                    .putExtra("newForTable", true)
            )
        }
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
                val currency = "MOP"
                h.txtState.text = "有單 ${open.localOrderNo} · ${currency}${String.format(Locale.US, "%.2f", open.total)}"
                h.txtState.setTextColor(getColor(R.color.warn_red))
            } else {
                t.area?.let { h.txtState.text = "空閒 · $it" } ?: run { h.txtState.text = "空閒" }
                h.txtState.setTextColor(getColor(R.color.text_secondary))
            }
            h.itemView.setOnClickListener { openTable(t) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtTableName: TextView = v.findViewById(R.id.txtTableName)
            val txtState: TextView = v.findViewById(R.id.txtState)
        }
    }
}
