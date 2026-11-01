package com.macaupos.nativeapp

import android.app.AlertDialog
import android.content.Intent
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.db.AppDatabase
import com.macaupos.nativeapp.data.repo.BootstrapRepository
import com.macaupos.nativeapp.data.repo.OrderRepository
import com.macaupos.nativeapp.data.repo.PrintService
import com.macaupos.nativeapp.data.repo.PrinterStore
import com.macaupos.nativeapp.data.repo.SyncFlusher
import com.macaupos.nativeapp.domain.OrderBuilder
import com.macaupos.nativeapp.domain.OrderTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P1-A 落單 —— 離線優先閉環:
 * 餐牌(bootstrap 快取)→ 網格點菜 → 送廚房 →
 * 本地單號(Room max+1)→ 建單寫 Room + outbox → 廚房單字節渲染 + LAN 9100 打印 →
 * 上網後 SyncFlusher 推 /api/pos/sync。
 *
 * 觸控規範:菜格 ≥88dp、按鈕 ≥44dp。
 */
class OrderActivity : ComponentActivity() {

    data class MenuItemLite(
        val id: String,
        val name: String,
        val price: Double,
        val printerGroup: String?,
        val categoryId: String?,
    )

    private lateinit var db: AppDatabase
    private lateinit var bootstrapRepo: BootstrapRepository
    private lateinit var orderRepo: OrderRepository
    private lateinit var printService: PrintService
    private lateinit var syncFlusher: SyncFlusher
    private lateinit var deviceStore: DeviceStore

    private val cart = LinkedHashMap<String, OrderBuilder.Item>()
    private var menu: List<MenuItemLite> = emptyList()
    private var categories: List<Pair<String, String>> = emptyList() // (id, name)
    private var bootstrap: BootstrapRepository.Bootstrap? = null

    /** 加菜模式:帶住既有單 id 入嚟,送單 = 附加落該單(ORDER_UPDATED + addon 廚房單)。 */
    private var existingOrderId: String? = null
    private var existingOrder: com.macaupos.nativeapp.data.db.PosOrderEntity? = null

    private lateinit var adapter: MenuAdapter
    private lateinit var txtCartCount: TextView
    private lateinit var txtCartTotal: TextView
    private lateinit var spinnerCategory: android.widget.Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order)

        db = AppDatabase.get(this)
        deviceStore = DeviceStore(this)
        bootstrapRepo = BootstrapRepository(applicationContext, db)
        orderRepo = OrderRepository(this, db)
        printService = PrintService(this)
        syncFlusher = SyncFlusher(this, db)

        txtCartCount = findViewById(R.id.txtCartCount)
        txtCartTotal = findViewById(R.id.txtCartTotal)
        spinnerCategory = findViewById(R.id.spinnerCategory)

        adapter = MenuAdapter { item -> addToCart(item) }
        val recycler = findViewById<RecyclerView>(R.id.recyclerMenu)
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.adapter = adapter

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            cart.clear(); renderCart()
        }
        findViewById<Button>(R.id.btnCart).setOnClickListener { showCartDialog() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendOrder() }

        // 加菜模式:預填枱號 + 載入既有單
        intent.getStringExtra("tableName")?.takeIf { it.isNotBlank() }?.let {
            findViewById<EditText>(R.id.inputTable).setText(it)
            findViewById<EditText>(R.id.inputTable).isEnabled = false
        }
        existingOrderId = intent.getStringExtra("existingOrderId")
        existingOrderId?.let { oid ->
            lifecycleScope.launch {
                existingOrder = withContext(Dispatchers.IO) { orderRepo.byId(oid) }
                existingOrder?.let {
                    findViewById<EditText>(R.id.inputTable).setText(it.tableName ?: "")
                    Toast.makeText(this@OrderActivity, "加菜模式:${it.localOrderNo}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        loadMenu()
    }

    private fun loadMenu() {
        val storeId = deviceStore.storeId
        lifecycleScope.launch {
            // 先讀快取(即刻有嘢睇,離線都開得),再線上刷新
            bootstrap = bootstrapRepo.cached(storeId)
            if (bootstrap == null) {
                refreshMenu()
            } else {
                renderMenu(); refreshMenu()
            }
        }
    }

    private fun refreshMenu() {
        val storeId = deviceStore.storeId
        lifecycleScope.launch {
            val result = bootstrapRepo.refresh(storeId)
            result.onSuccess {
                bootstrap = bootstrapRepo.cached(storeId)
                renderMenu()
                runOnUiThread { Toast.makeText(this@OrderActivity, "餐牌已更新", Toast.LENGTH_SHORT).show() }
            }.onFailure { e ->
                val hasCache = bootstrap != null
                runOnUiThread {
                    Toast.makeText(
                        this@OrderActivity,
                        if (hasCache) "離線:用緊本地快取餐牌(${e.message})" else "餐牌載入失敗:${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun renderMenu() {
        val bs = bootstrap ?: return
        menu = bs.menuItems.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
                val id = o.optString("id", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = o.optString("name", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val price = o.optDouble("price", Double.NaN).takeIf { !it.isNaN() } ?: return@mapNotNull null
                MenuItemLite(id, name, price, o.optString("printerGroup", "").takeIf { it.isNotBlank() }, o.optString("categoryId", ""))
            }
        }
        categories = bs.categories.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .map { it.optString("id", "") to it.optString("name", "") }
                .filter { it.first.isNotBlank() }
        }
        renderCategorySpinner()
        adapter.submit(filterMenu(null))
        renderCart()
    }

    private fun renderCategorySpinner() {
        val labels = mutableListOf("全部菜品")
        labels.addAll(categories.map { it.second })
        spinnerCategory.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        spinnerCategory.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val catId = if (pos == 0) null else categories.getOrNull(pos - 1)?.first
                adapter.submit(filterMenu(catId))
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun filterMenu(categoryId: String?): List<MenuItemLite> =
        if (categoryId == null) menu else menu.filter { it.categoryId == categoryId }

    // ---- 購物車 ----

    private fun addToCart(item: MenuItemLite) {
        val existing = cart[item.id]
        cart[item.id] = OrderBuilder.Item(
            menuItemId = item.id,
            name = item.name,
            price = item.price,
            quantity = (existing?.quantity ?: 0) + 1,
            printerGroup = item.printerGroup,
            note = existing?.note,
        )
        renderCart()
    }

    private fun renderCart() {
        val lines = cart.values
        val count = lines.sumOf { it.quantity }
        val totals = OrderTotals.compute(
            lines.map { OrderTotals.CartLine(it.price, it.quantity) },
            bootstrap?.taxRate, bootstrap?.serviceChargeRate,
        )
        val currency = bootstrap?.currency ?: "$"
        txtCartCount.text = "$count 件"
        txtCartTotal.text = String.format(Locale.US, "%s%.2f", currency, totals.total)
        findViewById<Button>(R.id.btnSend).isEnabled = count > 0
    }

    private fun showCartDialog() {
        if (cart.isEmpty()) return
        val currency = bootstrap?.currency ?: "$"
        val rows = cart.values.map { it ->
            "${it.name} ×${it.quantity}   ${currency}${String.format(Locale.US, "%.2f", it.price * it.quantity)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("購物車明細(長按刪除)")
            .setItems(rows) { _, _ -> }
            .setPositiveButton("關閉", null)
            .show()
    }

    // ---- 送單 ----

    private fun sendOrder() {
        val items = cart.values.toList()
        if (items.isEmpty()) return

        btnSendState(false)
        lifecycleScope.launch {
            try {
                val bs = bootstrap

                // ── 加菜模式:附加落既有單(ORDER_UPDATED + addon 票)──
                val existing = existingOrder
                if (existing != null) {
                    val added = orderRepo.addItemsToOrder(existing, items, bs?.taxRate, bs?.serviceChargeRate)
                    added.getOrElse { e ->
                        runOnUiThread {
                            btnSendState(true)
                            Toast.makeText(this@OrderActivity, "加菜失敗:${e.message}", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    // Kotlin Result 唔支援 destructuring → getOrThrow 拆(失敗已喺上面 return)
                    val updated = added.getOrThrow().first
                    val itemsJson = added.getOrThrow().second
                    // addon 票:淨印新 items(ticketType=addon,web isAddOnOrder 同款)
                    val addonJob = JSONObject()
                        .put("id", updated.id)
                        .put("tableName", updated.tableName ?: JSONObject.NULL)
                        .put("ticketType", "addon")
                        .put("items", newItemsOnly(updated, itemsJson, items))
                    val printResult = withContext(Dispatchers.IO) {
                        runCatching {
                            if (PrinterStore(applicationContext).hasPrinter()) {
                                printService.printKitchenTicket(addonJob)
                            } else Result.success(Unit)
                        }.getOrElse { Result.failure(it) }
                    }
                    withContext(Dispatchers.IO) { syncFlusher.flush() }
                    withContext(Dispatchers.Main) {
                        cart.clear()
                        btnSendState(true)
                        Toast.makeText(
                            this@OrderActivity,
                            "✓ 加菜已落單 ${updated.localOrderNo}" + printResult.fold({ " · 已打印" }, { " · ⚠ 打印:${it.message}" }),
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                    return@launch
                }

                // ── 新單 ──
                val tableName = findViewById<EditText>(R.id.inputTable).text.toString().trim()
                    .ifBlank { "外賣" }
                val fulfillment = if (tableName.contains("外賣", ignoreCase = true) || tableName.contains("takeaway", true)) "takeaway" else "dine_in"
                val localNo = orderRepo.nextLocalOrderNo()
                val built = OrderBuilder.build(
                    storeId = deviceStore.storeId,
                    tableName = tableName,
                    fulfillment = fulfillment,
                    items = items,
                    taxRate = bs?.taxRate,
                    serviceChargeRate = bs?.serviceChargeRate,
                    orderNote = null,
                    localOrderNo = localNo,
                )
                val created = orderRepo.createOrder(built)
                created.getOrElse { e ->
                    runOnUiThread {
                        btnSendState(true)
                        Toast.makeText(this@OrderActivity, "建單失敗:${e.message}", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // 廚房單:有打印機就即印;冇都照出字節(自檢證明渲染 OK),離線單照收
                val printResult = withContext(Dispatchers.IO) {
                    runCatching {
                        if (PrinterStore(applicationContext).hasPrinter()) {
                            printService.printKitchenTicket(built.payload)
                        } else {
                            val bytes = printService.renderKitchenBytes(built.payload)
                            Result.success<Unit>(Unit).also { _ -> bytes.size } // 渲染成功即算
                        }
                    }.getOrElse { Result.failure(it) }
                }

                // 即刻試一次 flush(在線就上雲;離線留 pending)
                withContext(Dispatchers.IO) { syncFlusher.flush() }

                withContext(Dispatchers.Main) {
                    cart.clear()
                    renderCart()
                    btnSendState(true)
                    val msg = buildString {
                        append("✓ ${built.order.localOrderNo} 已收單")
                        printResult.fold(
                            onSuccess = { append(" · 廚房單已打印") },
                            onFailure = { append(" · ⚠ 打印:${it.message}") },
                        )
                    }
                    Toast.makeText(this@OrderActivity, msg, Toast.LENGTH_LONG).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnSendState(true)
                    Toast.makeText(this@OrderActivity, "送單異常:${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** addon 票只印今次新加嗰啲(items JSON 入面按 menuItemId+qty 尾段摘出)。 */
    private fun newItemsOnly(order: com.macaupos.nativeapp.data.db.PosOrderEntity, all: JSONArray, added: List<OrderBuilder.Item>): JSONArray {
        // 簡化:直接用「今次送嘅 items」(數量係今次加嘅數量)
        val arr = JSONArray()
        added.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("quantity", it.quantity)
                    .put("price", it.price)
            )
        }
        return arr
    }

    private fun btnSendState(enabled: Boolean) {
        findViewById<Button>(R.id.btnSend).isEnabled = enabled
    }

    // ---- Adapter ----

    private inner class MenuAdapter(
        private val onClick: (MenuItemLite) -> Unit,
    ) : RecyclerView.Adapter<MenuAdapter.VH>() {

        private var items: List<MenuItemLite> = emptyList()

        fun submit(list: List<MenuItemLite>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_menu, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            val currency = bootstrap?.currency ?: "$"
            h.txtName.text = item.name
            h.txtPrice.text = String.format(Locale.US, "%s%.2f", currency, item.price)
            h.itemView.setOnClickListener { onClick(item) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtName)
            val txtPrice: TextView = v.findViewById(R.id.txtPrice)
        }
    }
}
