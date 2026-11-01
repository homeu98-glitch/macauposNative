package com.macaupos.nativeapp

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.db.AppDatabase
import com.macaupos.nativeapp.data.repo.MenuAdminRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 餐牌管理 —— 對齊 web device-settings 餐牌子模組嘅**核心**:
 * 菜品改名 / 改價 / 新增 / 刪除 → `POST /api/pos/bootstrap`(整份餐牌覆寫)。
 *
 * ⚠️ server 係 upsert 整個 row,所以每次都帶齊 categories/menuItems/tables/rules/
 *    printerGroups(本機快取原樣保留,只換 menuItems)。
 * 分類管理、規格、菜品圖片屬後續迭代。
 */
class MenuAdminActivity : ComponentActivity() {

    data class Item(var id: String, var name: String, var price: Double, var categoryId: String)

    private lateinit var db: AppDatabase
    private lateinit var deviceStore: DeviceStore
    private lateinit var repo: MenuAdminRepository

    private var payload: JSONObject = JSONObject()
    private var categories: List<Pair<String, String>> = emptyList()
    private val items = mutableListOf<Item>()
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu_admin)

        db = AppDatabase.get(this)
        deviceStore = DeviceStore(this)
        repo = MenuAdminRepository(deviceStore, db)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { addItem() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAll() }

        adapter = Adapter()
        findViewById<RecyclerView>(R.id.recyclerMenuAdmin).apply {
            layoutManager = LinearLayoutManager(this@MenuAdminActivity)
            adapter = this@MenuAdminActivity.adapter
        }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { repo.current() }
            if (p == null) {
                Toast.makeText(this@MenuAdminActivity, "本機未有餐牌快取,請先入落單頁同步一次", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            payload = p
            categories = p.optJSONArray("categories")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .map { it.optString("id") to it.optString("name") }
                    .filter { it.first.isNotBlank() }
            } ?: emptyList()
            items.clear()
            p.optJSONArray("menuItems")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    items.add(
                        Item(
                            id = o.optString("id"),
                            name = o.optString("name"),
                            price = o.optDouble("price", 0.0),
                            categoryId = o.optString("categoryId", ""),
                        )
                    )
                }
            }
            findViewById<TextView>(R.id.txtHint).text =
                "${items.size} 個菜品 · ${categories.size} 個分類 · 改完記得儲存"
            adapter.notifyDataSetChanged()
        }
    }

    private fun catName(id: String) = categories.firstOrNull { it.first == id }?.second ?: "未分類"

    private fun edit(item: Item) {
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(arrayOf("改名", "改價", "改分類", "刪除")) { _, which ->
                when (which) {
                    0 -> inputDialog("改名", item.name) { v ->
                        if (v.isNotBlank()) { item.name = v; adapter.notifyDataSetChanged() }
                    }
                    1 -> inputDialog("改價", String.format(Locale.US, "%.2f", item.price)) { v ->
                        v.toDoubleOrNull()?.let { price ->
                            if (price >= 0) { item.price = price; adapter.notifyDataSetChanged() }
                        }
                    }
                    2 -> pickCategory(item)
                    3 -> AlertDialog.Builder(this)
                        .setTitle("刪除 ${item.name}?")
                        .setPositiveButton("刪除") { _, _ ->
                            items.remove(item); adapter.notifyDataSetChanged()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickCategory(item: Item) {
        if (categories.isEmpty()) {
            Toast.makeText(this, "未有分類,請先用 web 建立分類", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = categories.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${item.name} · 分類")
            .setItems(labels) { _, which ->
                item.categoryId = categories[which].first
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addItem() {
        if (categories.isEmpty()) {
            Toast.makeText(this, "未有分類,請先用 web 建立分類", Toast.LENGTH_SHORT).show()
            return
        }
        val name = EditText(this).apply { hint = "菜品名" }
        val price = EditText(this).apply {
            hint = "價格"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(name)
            addView(price)
        }
        val labels = categories.map { it.second }.toTypedArray()
        var picked = 0
        AlertDialog.Builder(this)
            .setTitle("新增菜品")
            .setView(box)
            .setPositiveButton("下一步") { _, _ ->
                val n = name.text.toString().trim()
                val p = price.text.toString().trim().toDoubleOrNull()
                if (n.isBlank() || p == null || p < 0) {
                    Toast.makeText(this, "名同價錢要填好", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                AlertDialog.Builder(this)
                    .setTitle("揀分類")
                    .setItems(labels) { _, which ->
                        picked = which
                        items.add(
                            Item(
                                id = "item-" + System.currentTimeMillis().toString(36),
                                name = n,
                                price = p,
                                categoryId = categories[picked].first,
                            )
                        )
                        adapter.notifyDataSetChanged()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun inputDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("確定") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveAll() {
        findViewById<Button>(R.id.btnSave).isEnabled = false
        // 由現有 payload 重建 menuItems(保留其他欄位原樣 —— server 係整 row 覆寫)
        val arr = JSONArray()
        items.forEach { row ->
            arr.put(
                JSONObject()
                    .put("id", row.id)
                    .put("name", row.name)
                    .put("price", row.price)
                    .put("categoryId", row.categoryId)
                    .put("printerGroup", "kitchen")
                    .put("customerOrderable", true)
            )
        }
        payload.put("menuItems", arr)

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { repo.save(payload) }
            findViewById<Button>(R.id.btnSave).isEnabled = true
            r.onSuccess {
                Toast.makeText(this@MenuAdminActivity, "✓ 已儲存並同步到雲端", Toast.LENGTH_LONG).show()
                finish()
            }.onFailure {
                Toast.makeText(this@MenuAdminActivity, "儲存失敗:${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_soldout, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val row = items[pos]
            h.txtName.text = row.name
            h.txtPrice.text = String.format(Locale.US, "MOP%.2f", row.price)
            h.txtState.text = catName(row.categoryId)
            h.txtState.setBackgroundResource(R.drawable.bg_pill_light)
            h.txtState.setTextColor(getColor(R.color.text_secondary))
            h.itemView.setOnClickListener { edit(row) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtItemName)
            val txtPrice: TextView = v.findViewById(R.id.txtItemPrice)
            val txtState: TextView = v.findViewById(R.id.txtState)
        }
    }
}
