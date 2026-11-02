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
import java.util.UUID

/**
 * 餐牌管理 —— 對齊 web device-settings 餐牌子模組:
 *
 * - **菜品**:改名 / 改價 / 改分類 / 規格群組 / 刪除 / 新增
 * - **分類**:新增 / 改名 / 刪除(有菜品用緊就唔准刪)
 * - 規格:群組 `{id,name,selectionMode(single|multi),required,options:[{id,label,priceDelta}]}`
 *   同 web `MenuSpecGroup` 完全一致
 *
 * 🔴 全部改動都喺**原始 JSON** 上做(唔重建 object),保留未知欄位
 *    (memberPrice/image/printerGroup 等),避免「儲存一次清走一堆設定」。
 * 儲存 = `POST /api/pos/bootstrap` 整份餐牌覆寫(server 係 upsert 一店一 row)。
 */
class MenuAdminActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private lateinit var deviceStore: DeviceStore
    private lateinit var repo: MenuAdminRepository

    private var payload: JSONObject = JSONObject()
    private val items = mutableListOf<JSONObject>()
    private val cats = mutableListOf<JSONObject>()
    private var mode = "items" // items | categories
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu_admin)

        db = AppDatabase.get(this)
        deviceStore = DeviceStore(this)
        repo = MenuAdminRepository(deviceStore, db)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            if (mode == "items") addItem() else addCategory()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAll() }
        findViewById<TextView>(R.id.tabItems).setOnClickListener { setMode("items") }
        findViewById<TextView>(R.id.tabCategories).setOnClickListener { setMode("categories") }

        adapter = Adapter()
        findViewById<RecyclerView>(R.id.recyclerMenuAdmin).apply {
            layoutManager = LinearLayoutManager(this@MenuAdminActivity)
            adapter = this@MenuAdminActivity.adapter
        }
        load()
    }

    private fun setMode(next: String) {
        mode = next
        val onItems = next == "items"
        findViewById<TextView>(R.id.tabItems).apply {
            setBackgroundResource(if (onItems) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
            setTextColor(getColor(if (onItems) R.color.card else R.color.slate_700))
        }
        findViewById<TextView>(R.id.tabCategories).apply {
            setBackgroundResource(if (!onItems) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
            setTextColor(getColor(if (!onItems) R.color.card else R.color.slate_700))
        }
        findViewById<Button>(R.id.btnAdd).text = if (onItems) "新增菜品" else "新增分類"
        render()
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
            items.clear()
            p.optJSONArray("menuItems")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(JSONObject(it.toString())) }
            }
            cats.clear()
            p.optJSONArray("categories")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { cats.add(JSONObject(it.toString())) }
            }
            setMode("items")
        }
    }

    private fun render() {
        findViewById<TextView>(R.id.txtHint).text =
            if (mode == "items") "${items.size} 個菜品 · ${cats.size} 個分類 · 改完記得儲存"
            else "${cats.size} 個分類 · 有菜品用緊嘅分類唔可以刪"
        adapter.notifyDataSetChanged()
    }

    private fun catName(id: String) = cats.firstOrNull { it.optString("id") == id }?.optString("name")
        ?: "未分類"

    // ---- 菜品 ----

    private fun editItem(item: JSONObject) {
        val name = item.optString("name", "?")
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("改名", "改價", "改分類", "規格", "刪除")) { _, which ->
                when (which) {
                    0 -> input("改名", item.optString("name")) { v ->
                        if (v.isNotBlank()) { item.put("name", v); render() }
                    }
                    1 -> input("改價", String.format(Locale.US, "%.2f", item.optDouble("price", 0.0))) { v ->
                        v.toDoubleOrNull()?.let { p ->
                            if (p >= 0) { item.put("price", p); render() }
                        }
                    }
                    2 -> pickCategory(item)
                    3 -> specManager(item)
                    4 -> AlertDialog.Builder(this)
                        .setTitle("刪除 $name?")
                        .setPositiveButton("刪除") { _, _ -> items.remove(item); render() }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickCategory(item: JSONObject) {
        if (cats.isEmpty()) {
            Toast.makeText(this, "未有分類,請先喺「分類」分頁新增", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = cats.map { it.optString("name") }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${item.optString("name")} · 分類")
            .setItems(labels) { _, which ->
                item.put("categoryId", cats[which].optString("id"))
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addItem() {
        if (cats.isEmpty()) {
            Toast.makeText(this, "未有分類,請先喺「分類」分頁新增", Toast.LENGTH_SHORT).show()
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
                val labels = cats.map { it.optString("name") }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("揀分類")
                    .setItems(labels) { _, which ->
                        items.add(
                            JSONObject()
                                .put("id", "item-" + UUID.randomUUID().toString().take(8))
                                .put("name", n)
                                .put("price", p)
                                .put("categoryId", cats[which].optString("id"))
                                .put("printerGroup", "kitchen")
                                .put("customerOrderable", true)
                        )
                        render()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 規格 ----

    private fun specManager(item: JSONObject) {
        val groups = item.optJSONArray("specGroups") ?: JSONArray().also { item.put("specGroups", it) }
        val labels = ArrayList<String>()
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            val optCount = g.optJSONArray("options")?.length() ?: 0
            val mode = if (g.optString("selectionMode") == "multi") "多選" else "單選"
            val required = if (g.optBoolean("required", false)) "必選" else "可選"
            labels.add("${g.optString("name")} · $mode/$required · $optCount 個選項")
        }
        labels.add("＋ 新增規格群組")
        AlertDialog.Builder(this)
            .setTitle("${item.optString("name")} · 規格")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == labels.size - 1) addSpecGroup(item, groups)
                else editSpecGroup(item, groups, which)
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    private fun addSpecGroup(item: JSONObject, groups: JSONArray) {
        val name = EditText(this).apply { hint = "群組名(例:辣度 / 加料)" }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(name)
        }
        AlertDialog.Builder(this)
            .setTitle("新增規格群組")
            .setView(box)
            .setPositiveButton("下一步") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isBlank()) return@setPositiveButton
                AlertDialog.Builder(this)
                    .setTitle("$n · 選擇方式")
                    .setItems(arrayOf("單選(只可揀一個)", "多選(可揀多個)")) { _, which ->
                        val group = JSONObject()
                            .put("id", "spec-" + UUID.randomUUID().toString().take(8))
                            .put("name", n)
                            .put("selectionMode", if (which == 1) "multi" else "single")
                            .put("required", false)
                            .put("options", JSONArray())
                        groups.put(group)
                        addSpecOption(group) { render() }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editSpecGroup(item: JSONObject, groups: JSONArray, index: Int) {
        val g = groups.optJSONObject(index) ?: return
        val gName = g.optString("name")
        AlertDialog.Builder(this)
            .setTitle(gName)
            .setItems(arrayOf("改名", "切換單選/多選", "切換必選", "新增選項", "刪除群組")) { _, which ->
                when (which) {
                    0 -> input("群組改名", gName) { v ->
                        if (v.isNotBlank()) { g.put("name", v); specManager(item) }
                    }
                    1 -> {
                        g.put("selectionMode", if (g.optString("selectionMode") == "multi") "single" else "multi")
                        specManager(item)
                    }
                    2 -> {
                        g.put("required", !g.optBoolean("required", false))
                        specManager(item)
                    }
                    3 -> addSpecOption(g) { specManager(item) }
                    4 -> {
                        groups.remove(index)
                        specManager(item)
                    }
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun addSpecOption(group: JSONObject, onDone: () -> Unit) {
        val label = EditText(this).apply { hint = "選項名(例:小辣)" }
        val delta = EditText(this).apply {
            hint = "加價(可空,例 2)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(label)
            addView(delta)
        }
        AlertDialog.Builder(this)
            .setTitle("${group.optString("name")} · 新增選項")
            .setView(box)
            .setPositiveButton("加入") { _, _ ->
                val l = label.text.toString().trim()
                if (l.isBlank()) return@setPositiveButton
                val d = delta.text.toString().trim().toDoubleOrNull() ?: 0.0
                val opts = group.optJSONArray("options") ?: JSONArray().also { group.put("options", it) }
                opts.put(
                    JSONObject()
                        .put("id", "opt-" + UUID.randomUUID().toString().take(8))
                        .put("label", l)
                        .put("priceDelta", d)
                )
                onDone()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 分類 ----

    private fun editCategory(cat: JSONObject) {
        val name = cat.optString("name")
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("改名", "刪除")) { _, which ->
                when (which) {
                    0 -> input("分類改名", name) { v ->
                        if (v.isNotBlank()) { cat.put("name", v); render() }
                    }
                    1 -> {
                        val id = cat.optString("id")
                        val used = items.count { it.optString("categoryId") == id }
                        if (used > 0) {
                            Toast.makeText(this, "仲有 $used 個菜品用緊,唔可以刪", Toast.LENGTH_LONG).show()
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("刪除分類 $name?")
                                .setPositiveButton("刪除") { _, _ -> cats.remove(cat); render() }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addCategory() {
        input("新增分類", "") { v ->
            if (v.isNotBlank()) {
                cats.add(
                    JSONObject()
                        .put("id", "cat-" + UUID.randomUUID().toString().take(8))
                        .put("name", v)
                )
                render()
            }
        }
    }

    private fun input(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("確定") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 儲存 ----

    private fun saveAll() {
        findViewById<Button>(R.id.btnSave).isEnabled = false
        val itemsArr = JSONArray()
        items.forEach { itemsArr.put(it) }
        val catsArr = JSONArray()
        cats.forEach { catsArr.put(it) }
        payload.put("menuItems", itemsArr)
        payload.put("categories", catsArr)

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

        override fun getItemCount(): Int = if (mode == "items") items.size else cats.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            if (mode == "items") {
                val item = items[pos]
                h.txtName.text = item.optString("name", "?")
                val specs = item.optJSONArray("specGroups")?.length() ?: 0
                h.txtPrice.text = String.format(Locale.US, "MOP%.2f", item.optDouble("price", 0.0)) +
                    (if (specs > 0) " · $specs 組規格" else "")
                h.txtState.text = catName(item.optString("categoryId"))
                h.txtState.setBackgroundResource(R.drawable.bg_pill_light)
                h.txtState.setTextColor(getColor(R.color.text_secondary))
                h.itemView.setOnClickListener { editItem(item) }
            } else {
                val cat = cats[pos]
                val used = items.count { it.optString("categoryId") == cat.optString("id") }
                h.txtName.text = cat.optString("name", "?")
                h.txtPrice.text = "$used 個菜品"
                h.txtState.text = "編輯"
                h.txtState.setBackgroundResource(R.drawable.bg_pill_light)
                h.txtState.setTextColor(getColor(R.color.orange_600))
                h.itemView.setOnClickListener { editCategory(cat) }
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtItemName)
            val txtPrice: TextView = v.findViewById(R.id.txtItemPrice)
            val txtState: TextView = v.findViewById(R.id.txtState)
        }
    }
}
