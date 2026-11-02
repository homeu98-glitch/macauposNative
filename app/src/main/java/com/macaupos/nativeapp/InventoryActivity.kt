package com.macaupos.nativeapp

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.repo.InventoryClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 庫存總覽 —— 對齊 web 庫存頁(唯讀):
 * 品項 / 分類 / 現存+單位 / 平均成本 / **低庫存警示**(current_qty <= reorder_level)。
 */
class InventoryActivity : ComponentActivity() {

    private lateinit var client: InventoryClient
    private var all: List<InventoryClient.Product> = emptyList()
    private var shown: List<InventoryClient.Product> = emptyList()
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inventory)
        client = InventoryClient(DeviceStore(this))

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { load() }

        adapter = Adapter()
        findViewById<RecyclerView>(R.id.recyclerInventory).apply {
            layoutManager = LinearLayoutManager(this@InventoryActivity)
            adapter = this@InventoryActivity.adapter
        }
        findViewById<EditText>(R.id.inputSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString()?.trim().orEmpty())
            }
        })
        load()
    }

    private fun load() {
        findViewById<TextView>(R.id.txtHint).text = "載入中…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { client.list() }
            when (r) {
                is InventoryClient.Result.Ok -> {
                    all = r.products
                    val low = all.count { it.lowStock }
                    findViewById<TextView>(R.id.txtHint).text =
                        "${all.size} 個品項 · 低庫存 $low(入貨／盤點用 web)"
                    applyFilter(findViewById<EditText>(R.id.inputSearch).text.toString().trim())
                }
                is InventoryClient.Result.Offline ->
                    findViewById<TextView>(R.id.txtHint).text = "離線:載入唔到庫存"
                is InventoryClient.Result.Fail ->
                    findViewById<TextView>(R.id.txtHint).text = "載入失敗:${r.error}"
            }
        }
    }

    private fun applyFilter(keyword: String) {
        shown = if (keyword.isBlank()) all
        else all.filter { it.name.contains(keyword, true) || (it.category?.contains(keyword, true) == true) }
        adapter.notifyDataSetChanged()
    }

    /** 盤點:設定新庫存量(對齊 web 庫存「盤點」)。 */
    private fun adjust(p: InventoryClient.Product) {
        val qty = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.2f", p.currentQty))
        }
        val reason = EditText(this).apply { hint = "原因(可空,預設 POS 盤點)" }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(android.widget.TextView(this@InventoryActivity).apply {
                text = "現存 ${p.currentQty} ${p.unit}"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
            })
            addView(qty)
            addView(reason)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("盤點 · ${p.name}")
            .setView(box)
            .setPositiveButton("儲存") { _, _ ->
                val v = qty.text.toString().trim().toDoubleOrNull()
                if (v == null || v < 0) {
                    android.widget.Toast.makeText(this, "請輸入有效數量", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) {
                        client.adjust(p.id, v, reason.text.toString().trim().ifBlank { null })
                    }
                    r.onSuccess {
                        android.widget.Toast.makeText(this@InventoryActivity, "✓ 已更新庫存", android.widget.Toast.LENGTH_SHORT).show()
                        load()
                    }.onFailure {
                        android.widget.Toast.makeText(this@InventoryActivity, "失敗:${it.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_soldout, parent, false))

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val p = shown[pos]
            h.txtName.text = p.name
            h.txtPrice.text = String.format(
                Locale.US,
                "%s · 現存 %.2f %s · 均價 MOP%.2f",
                p.category ?: "未分類",
                p.currentQty,
                p.unit,
                p.avgUnitCost,
            )
            when {
                !p.active -> {
                    h.txtState.text = "停用"
                    h.txtState.setTextColor(getColor(R.color.text_secondary))
                }
                p.lowStock -> {
                    h.txtState.text = "低庫存"
                    h.txtState.setBackgroundResource(R.drawable.bg_badge_count)
                    h.txtState.setTextColor(getColor(R.color.card))
                }
                else -> {
                    h.txtState.text = "正常"
                    h.txtState.setBackgroundResource(R.drawable.bg_pill_light)
                    h.txtState.setTextColor(getColor(R.color.ok_green))
                }
            }
            h.itemView.setOnClickListener { adjust(p) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtItemName)
            val txtPrice: TextView = v.findViewById(R.id.txtItemPrice)
            val txtState: TextView = v.findViewById(R.id.txtState)
        }
    }
}
