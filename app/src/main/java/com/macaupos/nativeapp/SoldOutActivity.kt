package com.macaupos.nativeapp

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.SoldOutStore
import com.macaupos.nativeapp.data.db.AppDatabase
import com.macaupos.nativeapp.data.repo.BootstrapRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 沽清管理 —— 對齊 web 本機沽清語意(`pos-soldout-changed` + localStorage):
 * 每道菜可以「沽清」或者「設定剩餘份數」;落單頁讀同一份狀態,
 * 售罄菜唔可以加入、有剩餘會顯示「只剩 N 份」。
 *
 * (web 目前亦係本機狀態,`/api/inventory/soldout` 仲係 TODO stub —— 兩邊一致。)
 */
class SoldOutActivity : ComponentActivity() {

    data class Row(val id: String, val name: String, val price: Double)

    private lateinit var db: AppDatabase
    private lateinit var store: SoldOutStore
    private lateinit var bootstrapRepo: BootstrapRepository
    private lateinit var deviceStore: DeviceStore

    private var rows: List<Row> = emptyList()
    private var states: Map<String, SoldOutStore.State> = emptyMap()
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_soldout)
        db = AppDatabase.get(this)
        store = SoldOutStore(this)
        deviceStore = DeviceStore(this)
        bootstrapRepo = BootstrapRepository(applicationContext, db)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        adapter = Adapter()
        findViewById<RecyclerView>(R.id.recyclerSoldOut).apply {
            layoutManager = LinearLayoutManager(this@SoldOutActivity)
            adapter = this@SoldOutActivity.adapter
        }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            var bs = bootstrapRepo.cached(deviceStore.storeId)
            if (bs == null) {
                bootstrapRepo.refresh(deviceStore.storeId)
                bs = bootstrapRepo.cached(deviceStore.storeId)
            }
            if (bs == null) {
                Toast.makeText(this@SoldOutActivity, "餐牌未載入,請先聯網同步一次", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            rows = bs.menuItems.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
                    val id = o.optString("id", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val name = o.optString("name", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Row(id, name, o.optDouble("price", 0.0))
                }
            }
            states = withContext(Dispatchers.IO) { store.all() }
            findViewById<TextView>(R.id.txtHint).text =
                "共 ${rows.size} 道菜 · 已沽清 ${states.count { it.value.soldOut }}"
            adapter.notifyDataSetChanged()
        }
    }

    private fun edit(row: Row) {
        val cur = states[row.id]
        val label = when {
            cur == null -> "有貨"
            cur.soldOut -> "售罄"
            cur.remainingQty != null -> "只剩 ${cur.remainingQty} 份"
            else -> "有貨"
        }
        AlertDialog.Builder(this)
            .setTitle("${row.name} · $label")
            .setItems(arrayOf("設定剩餘份數", "沽清", "恢復有貨", "清除設定")) { _, which ->
                when (which) {
                    0 -> setRemaining(row)
                    1 -> {
                        store.setSoldOut(row.id, true)
                        reloadStates()
                    }
                    2 -> {
                        store.setSoldOut(row.id, false)
                        reloadStates()
                    }
                    3 -> {
                        store.clearItem(row.id)
                        reloadStates()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setRemaining(row: Row) {
        val input = EditText(this).apply {
            hint = "剩幾份(0 = 售罄)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(states[row.id]?.remainingQty?.toString() ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("${row.name} · 剩餘份數")
            .setView(input)
            .setPositiveButton("確定") { _, _ ->
                val n = input.text.toString().trim().toIntOrNull()
                if (n == null) {
                    Toast.makeText(this, "請輸入數字", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                store.setRemaining(row.id, n, states[row.id]?.initialQty ?: n)
                reloadStates()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun reloadStates() {
        states = store.all()
        findViewById<TextView>(R.id.txtHint).text =
            "共 ${rows.size} 道菜 · 已沽清 ${states.count { it.value.soldOut }}"
        adapter.notifyDataSetChanged()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_soldout, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val row = rows[pos]
            val st = states[row.id]
            h.txtName.text = row.name
            h.txtPrice.text = String.format(Locale.US, "MOP%.2f", row.price)
            when {
                st?.soldOut == true -> {
                    h.txtState.text = "售罄"
                    h.txtState.setBackgroundResource(R.drawable.bg_badge_count)
                    h.txtState.setTextColor(getColor(R.color.card))
                }
                st?.remainingQty != null -> {
                    h.txtState.text = "只剩 ${st.remainingQty} 份"
                    h.txtState.setBackgroundResource(R.drawable.bg_pill_light)
                    h.txtState.setTextColor(getColor(R.color.orange_600))
                }
                else -> {
                    h.txtState.text = "有貨"
                    h.txtState.setBackgroundResource(R.drawable.bg_pill_light)
                    h.txtState.setTextColor(getColor(R.color.ok_green))
                }
            }
            h.itemView.setOnClickListener { edit(row) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtItemName)
            val txtPrice: TextView = v.findViewById(R.id.txtItemPrice)
            val txtState: TextView = v.findViewById(R.id.txtState)
        }
    }
}
