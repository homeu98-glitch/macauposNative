package com.macaupos.nativeapp

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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

/**
 * 沽清(/soldout)—— 對齊 web `soldout-page`:
 *
 * - 2 tab:菜品沽清 / 規格沽清
 * - 菜品 tab:分類 chips + 分頁(每頁 20) + 表格(菜品/剩餘/設定數量/操作)
 * - 每行:設定數量(input)、清除、直接售罄
 * - 規格 tab:規格選項 checkbox 沽清(簡化:佔位)
 */
class SoldOutActivity : ComponentActivity() {

    data class Row(val id: String, val name: String, val price: Double, val categoryId: String?)

    private lateinit var db: AppDatabase
    private lateinit var store: SoldOutStore
    private lateinit var bootstrapRepo: BootstrapRepository
    private lateinit var deviceStore: DeviceStore

    private var rows: List<Row> = emptyList()
    private var categories: List<Pair<String, String>> = emptyList()
    private var states: Map<String, SoldOutStore.State> = emptyMap()
    private lateinit var adapter: Adapter

    private var tab = "items"
    private var categoryId = "all"
    private var page = 1
    private val pageSize = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_soldout)
        RailNav.attach(this)
        db = AppDatabase.get(this)
        store = SoldOutStore(this)
        deviceStore = DeviceStore(this)
        bootstrapRepo = BootstrapRepository(applicationContext, db)

        findViewById<android.widget.Button>(R.id.btnSave).setOnClickListener {
            findViewById<TextView>(R.id.txtStatus).text = "已保存沽清設定（本機）。"
        }

        adapter = Adapter()
        findViewById<RecyclerView>(R.id.recyclerSoldOut).apply {
            layoutManager = LinearLayoutManager(this@SoldOutActivity)
            adapter = this@SoldOutActivity.adapter
        }

        findViewById<TextView>(R.id.btnPrev).setOnClickListener {
            if (page > 1) { page--; applyFilter() }
        }
        findViewById<TextView>(R.id.btnNext).setOnClickListener {
            val totalPages = ((filteredRows().size + pageSize - 1) / pageSize).coerceAtLeast(1)
            if (page < totalPages) { page++; applyFilter() }
        }

        renderTabs()
        load()
    }

    private fun renderTabs() {
        val bar = findViewById<LinearLayout>(R.id.chipsTabs)
        bar.removeAllViews()
        listOf("items" to "菜品沽清", "specs" to "規格沽清").forEach { (key, label) ->
            val selected = key == tab
            val tv = TextView(this).apply {
                text = label
                minHeight = dp(36)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(if (selected) R.color.card else R.color.slate_700))
                background = getDrawable(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
                setOnClickListener {
                    tab = key
                    renderTabs()
                    findViewById<RecyclerView>(R.id.recyclerSoldOut).visibility =
                        if (key == "items") View.VISIBLE else View.GONE
                    findViewById<LinearLayout>(R.id.panelSpecs).visibility =
                        if (key == "specs") View.VISIBLE else View.GONE
                    findViewById<HorizontalScrollView>(R.id.scrollCategories).visibility =
                        if (key == "items") View.VISIBLE else View.GONE
                    findViewById<LinearLayout>(R.id.boxPaging).visibility =
                        if (key == "items") View.VISIBLE else View.GONE
                    findViewById<LinearLayout>(R.id.boxTableHead).visibility =
                        if (key == "items") View.VISIBLE else View.GONE
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(6)
            bar.addView(tv, lp)
        }
    }

    private fun renderCategories() {
        val bar = findViewById<LinearLayout>(R.id.chipsCategories)
        bar.removeAllViews()
        val items: List<Pair<String, String>> =
            listOf("all" to "全部") + categories
        items.forEach { (id, name) ->
            val selected = id == categoryId
            val tv = TextView(this).apply {
                text = name
                minHeight = dp(32)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(12), dp(5), dp(12), dp(5))
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(if (selected) R.color.card else R.color.slate_700))
                background = getDrawable(if (selected) R.drawable.bg_btn_dark else R.drawable.bg_chip_normal)
                setOnClickListener {
                    categoryId = id
                    page = 1
                    renderCategories()
                    applyFilter()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(6)
            bar.addView(tv, lp)
        }
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
                    Row(id, name, o.optDouble("price", 0.0), o.optString("categoryId", "").takeIf { it.isNotBlank() })
                }
            }
            categories = bs.categories.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .map { it.optString("id", "") to it.optString("name", "") }
                    .filter { it.first.isNotBlank() }
            }
            states = withContext(Dispatchers.IO) { store.all() }
            renderCategories()
            applyFilter()
        }
    }

    private fun filteredRows(): List<Row> =
        if (categoryId == "all") rows else rows.filter { it.categoryId == categoryId }

    private fun applyFilter() {
        val filtered = filteredRows()
        val totalPages = ((filtered.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        page = page.coerceIn(1, totalPages)
        val start = (page - 1) * pageSize
        val end = minOf(start + pageSize, filtered.size)
        val shown = if (start < filtered.size) filtered.subList(start, end) else emptyList()
        adapter.submit(shown)
        findViewById<TextView>(R.id.txtPageInfo).text =
            "共 ${filtered.size} 個菜品 · 第 $page/$totalPages 頁（每頁 $pageSize）"
        findViewById<TextView>(R.id.txtStatus).text =
            "共 ${rows.size} 道菜 · 已沽清 ${states.count { it.value.soldOut }}"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setQty(row: Row, qty: Int) {
        store.setRemaining(row.id, qty, states[row.id]?.initialQty ?: qty)
        states = store.all()
        applyFilter()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {

        private var shown: List<Row> = emptyList()

        fun submit(l: List<Row>) {
            shown = l
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_soldout_row, parent, false))

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val row = shown[pos]
            val st = states[row.id]
            val remaining = st?.remainingQty
            val isSoldOut = st?.soldOut == true || (remaining != null && remaining <= 0)

            h.txtName.text = row.name
            h.txtSoldOutBadge.visibility = if (isSoldOut) View.VISIBLE else View.GONE
            h.txtRemaining.text = remaining?.toString() ?: "--"
            h.inputQty.setText(st?.initialQty?.toString() ?: "")

            h.btnClear.setOnClickListener {
                store.clearItem(row.id)
                states = store.all()
                h.inputQty.setText("")
                applyFilter()
            }
            h.btnSoldOut.setOnClickListener {
                store.setSoldOut(row.id, true)
                states = store.all()
                applyFilter()
            }
            h.inputQty.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val n = h.inputQty.text.toString().trim().toIntOrNull()
                    if (n != null) setQty(row, n)
                }
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtName)
            val txtSoldOutBadge: TextView = v.findViewById(R.id.txtSoldOutBadge)
            val txtRemaining: TextView = v.findViewById(R.id.txtRemaining)
            val inputQty: EditText = v.findViewById(R.id.inputQty)
            val btnClear: TextView = v.findViewById(R.id.btnClear)
            val btnSoldOut: TextView = v.findViewById(R.id.btnSoldOut)
        }
    }
}
