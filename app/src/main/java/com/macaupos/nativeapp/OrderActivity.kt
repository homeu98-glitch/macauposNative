package com.macaupos.nativeapp

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
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
import java.util.Locale

/**
 * P1-A 落單 —— 離線優先閉環(web pos-app 雙欄佈局):
 * 分類 chips → 菜品網格 → 右側購物車(步進器)→ 送廚房 →
 * 本地單號(Room max+1)→ 建單寫 Room + outbox → 廚房單打印 → sync。
 *
 * 觸控規範:菜格 ≥88dp、步進按鈕 40dp、主按鈕 56dp。
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
    private var selectedCategory: String? = null

    /** 加菜模式:帶住既有單 id 入嚟,送單 = 附加落該單(ORDER_UPDATED + addon 廚房單)。 */
    private var existingOrderId: String? = null
    private var existingOrder: com.macaupos.nativeapp.data.db.PosOrderEntity? = null

    private lateinit var menuAdapter: MenuAdapter
    private lateinit var cartAdapter: CartAdapter
    private lateinit var txtCartTitle: TextView
    private lateinit var txtSubtotal: TextView
    private lateinit var txtTaxService: TextView
    private lateinit var txtCartTotal: TextView
    private lateinit var btnSend: Button
    private lateinit var txtSubtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order)

        db = AppDatabase.get(this)
        deviceStore = DeviceStore(this)
        bootstrapRepo = BootstrapRepository(applicationContext, db)
        orderRepo = OrderRepository(this, db)
        printService = PrintService(this)
        syncFlusher = SyncFlusher(this, db)

        txtCartTitle = findViewById(R.id.txtCartTitle)
        txtSubtotal = findViewById(R.id.txtSubtotal)
        txtTaxService = findViewById(R.id.txtTaxService)
        txtCartTotal = findViewById(R.id.txtCartTotal)
        btnSend = findViewById(R.id.btnSend)
        txtSubtitle = findViewById(R.id.txtSubtitle)

        menuAdapter = MenuAdapter { item -> addToCart(item) }
        findViewById<RecyclerView>(R.id.recyclerMenu).apply {
            layoutManager = GridLayoutManager(this@OrderActivity, 4)
            adapter = menuAdapter
        }

        cartAdapter = CartAdapter()
        findViewById<RecyclerView>(R.id.cartList).apply {
            layoutManager = LinearLayoutManager(this@OrderActivity)
            adapter = cartAdapter
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            cart.clear(); renderCart()
        }
        btnSend.setOnClickListener { sendOrder() }

        // 加菜模式:預填枱號 + 載入既有單
        intent.getStringExtra("tableName")?.takeIf { it.isNotBlank() }?.let {
            val input = findViewById<EditText>(R.id.inputTable)
            input.setText(it)
            input.isEnabled = false
            findViewById<TextView>(R.id.txtTitle).text = "加菜 · $it"
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
            }.onFailure { e ->
                val hasCache = bootstrap != null
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@OrderActivity,
                        if (hasCache) "離線:用緊本地快取餐牌" else "餐牌載入失敗:${e.message}",
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
        txtSubtitle.text = "${menu.size} 個菜品"
        renderCategoryChips()
        applyCategoryFilter()
        renderCart()
    }

    // ---- 分類 chips(對齊 web 圓膠囊)----

    private fun renderCategoryChips() {
        val bar = findViewById<LinearLayout>(R.id.chipsCategory)
        bar.removeAllViews()
        val items: List<Pair<String?, String>> =
            listOf<Pair<String?, String>>(null to "全部") + categories.map { it.first to it.second }
        items.forEach { (catId, label) ->
            val selected = catId == selectedCategory
            val tv = TextView(this).apply {
                text = label
                setPadding(28, 12, 28, 12)
                gravity = Gravity.CENTER
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(if (selected) R.color.card else R.color.text_secondary))
                background = getDrawable(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
                setOnClickListener {
                    selectedCategory = catId
                    renderCategoryChips()
                    applyCategoryFilter()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = 12
            bar.addView(tv, lp)
        }
    }

    private fun applyCategoryFilter() {
        menuAdapter.submit(
            if (selectedCategory == null) menu else menu.filter { it.categoryId == selectedCategory }
        )
    }

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

    private fun changeQty(item: OrderBuilder.Item, delta: Int) {
        val newQty = item.quantity + delta
        if (newQty <= 0) cart.remove(item.menuItemId)
        else cart[item.menuItemId] = item.copy(quantity = newQty)
        renderCart()
    }

    private fun renderCart() {
        val lines = cart.values.toList()
        val count = lines.sumOf { it.quantity }
        val totals = OrderTotals.compute(
            lines.map { OrderTotals.CartLine(it.price, it.quantity) },
            bootstrap?.taxRate, bootstrap?.serviceChargeRate,
        )
        val currency = bootstrap?.currency ?: "MOP"
        val money = { v: Double -> String.format(Locale.US, "%s%.2f", currency, v) }

        txtCartTitle.text = "購物車 · $count 件"
        txtSubtotal.text = "小計 ${money(totals.subtotal)}"
        val extras = buildList {
            if (totals.serviceChargeAmount > 0.0) add("服務費 ${money(totals.serviceChargeAmount)}")
            if (totals.taxAmount > 0.0) add("稅 ${money(totals.taxAmount)}")
        }
        txtTaxService.visibility = if (extras.isEmpty()) View.GONE else View.VISIBLE
        txtTaxService.text = extras.joinToString(" · ")
        txtCartTotal.text = money(totals.total)
        btnSend.isEnabled = count > 0
        cartAdapter.submit(lines)
    }

    // ---- 送單 ----

    private fun sendOrder() {
        val items = cart.values.toList()
        if (items.isEmpty()) return

        btnSend.isEnabled = false
        lifecycleScope.launch {
            try {
                val bs = bootstrap

                // ── 加菜模式:附加落既有單(ORDER_UPDATED + addon 票)──
                val existing = existingOrder
                if (existing != null) {
                    val added = orderRepo.addItemsToOrder(existing, items, bs?.taxRate, bs?.serviceChargeRate)
                    added.getOrElse { e ->
                        withContext(Dispatchers.Main) {
                            btnSend.isEnabled = true
                            Toast.makeText(this@OrderActivity, "加菜失敗:${e.message}", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
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
                        btnSend.isEnabled = true
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
                    withContext(Dispatchers.Main) {
                        btnSend.isEnabled = true
                        Toast.makeText(this@OrderActivity, "建單失敗:${e.message}", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // 廚房單:有打印機就即印;冇都照出字節,離線單照收
                val printResult = withContext(Dispatchers.IO) {
                    runCatching {
                        if (PrinterStore(applicationContext).hasPrinter()) {
                            printService.printKitchenTicket(built.payload)
                        } else {
                            printService.renderKitchenBytes(built.payload)
                            Result.success(Unit)
                        }
                    }.getOrElse { Result.failure(it) }
                }

                // 即刻試一次 flush(在線就上雲;離線留 pending)
                withContext(Dispatchers.IO) { syncFlusher.flush() }

                withContext(Dispatchers.Main) {
                    cart.clear()
                    renderCart()
                    btnSend.isEnabled = true
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
                    btnSend.isEnabled = true
                    Toast.makeText(this@OrderActivity, "送單異常:${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** addon 票只印今次新加嗰啲。 */
    private fun newItemsOnly(order: com.macaupos.nativeapp.data.db.PosOrderEntity, all: JSONArray, added: List<OrderBuilder.Item>): JSONArray {
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

    // ---- Adapters ----

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
            val currency = bootstrap?.currency ?: "MOP"
            h.txtName.text = item.name
            h.txtPrice.text = String.format(Locale.US, "%s%.2f", currency, item.price)
            h.itemView.setOnClickListener { onClick(item) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtName)
            val txtPrice: TextView = v.findViewById(R.id.txtPrice)
        }
    }

    /** 購物車行:+/- 步進;減到 0 移除。 */
    private inner class CartAdapter : RecyclerView.Adapter<CartAdapter.VH>() {

        private var lines: List<OrderBuilder.Item> = emptyList()

        fun submit(list: List<OrderBuilder.Item>) {
            lines = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_cart_line, parent, false))

        override fun getItemCount(): Int = lines.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = lines[pos]
            val currency = bootstrap?.currency ?: "MOP"
            h.txtName.text = item.name
            h.txtQty.text = item.quantity.toString()
            h.txtAmount.text = String.format(Locale.US, "%s%.2f", currency, item.price * item.quantity)
            h.btnPlus.setOnClickListener { changeQty(item, +1) }
            h.btnMinus.setOnClickListener { changeQty(item, -1) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtLineName)
            val txtQty: TextView = v.findViewById(R.id.txtQty)
            val txtAmount: TextView = v.findViewById(R.id.txtLineAmount)
            val btnPlus: TextView = v.findViewById(R.id.btnPlus)
            val btnMinus: TextView = v.findViewById(R.id.btnMinus)
        }
    }
}
