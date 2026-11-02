package com.macaupos.nativeapp

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import com.macaupos.nativeapp.data.JsonStore
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
 * 落單工作台 —— 對照 J 提供嘅 **web 實機截圖**(快餐模式):
 *
 *   深色導軌 68dp │ 訂單明細 226dp │ 餐牌 1fr │ 收銀與支付 240dp
 *
 * - 左欄:店名 + 店號 + 「快餐模式」pill → 訂單明細(件數)→ 堂食/外賣/自取 →
 *   購物車(空 = 虛線框「請從右側商品區加入菜品」)→ 全單備註 + 編輯
 * - 中欄:分類 chips + 搜尋商品 + 4 欄菜卡(MOP 細字 + 金額大字 + 「加入」藥丸 + xN 角標)
 * - 右欄:收銀與支付(小計/折扣/應收橙大字)→ 去結帳(深色)→ 下單(橙)→
 *   自動打印開關 → 打印廚房單 / 打印收據
 *
 * ⚠️ 截圖底部「線上訂單 / 線下訂單」面板屬 P2(未實作),呢版未有,唔好假裝有。
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
    private lateinit var prefs: JsonStore
    private lateinit var soldOutStore: com.macaupos.nativeapp.data.SoldOutStore
    private var soldOut: Map<String, com.macaupos.nativeapp.data.SoldOutStore.State> = emptyMap()

    private val cart = LinkedHashMap<String, OrderBuilder.Item>()
    private var menu: List<MenuItemLite> = emptyList()
    private var categories: List<Pair<String, String>> = emptyList()
    private var bootstrap: BootstrapRepository.Bootstrap? = null
    private var selectedCategory: String? = null
    private var searchKeyword = ""
    private var orderNote: String = ""
    /** dine_in / takeaway / pickup —— 對應左欄三個大掣 */
    private var fulfillment = "dine_in"
    private var autoPrint = true

    private var existingOrderId: String? = null
    private var existingOrder: com.macaupos.nativeapp.data.db.PosOrderEntity? = null

    private lateinit var menuAdapter: MenuAdapter
    private lateinit var cartAdapter: CartAdapter
    private lateinit var txtOrderCount: TextView
    private lateinit var txtSubtotal: TextView
    private lateinit var txtCartTotal: TextView
    private lateinit var txtPayHint: TextView
    private lateinit var txtOrderNote: TextView
    private lateinit var txtEmpty: TextView
    private lateinit var btnSend: Button
    private lateinit var btnDineIn: TextView
    private lateinit var btnTakeaway: TextView
    private lateinit var btnPickup: TextView
    private lateinit var inputTable: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order)

        db = AppDatabase.get(this)
        deviceStore = DeviceStore(this)
        bootstrapRepo = BootstrapRepository(applicationContext, db)
        orderRepo = OrderRepository(this, db)
        printService = PrintService(this)
        syncFlusher = SyncFlusher(this, db)
        prefs = JsonStore(this, "order-ui.json")
        soldOutStore = com.macaupos.nativeapp.data.SoldOutStore(this)

        txtOrderCount = findViewById(R.id.txtOrderCount)
        txtSubtotal = findViewById(R.id.txtSubtotal)
        txtCartTotal = findViewById(R.id.txtCartTotal)
        txtPayHint = findViewById(R.id.txtPayHint)
        txtOrderNote = findViewById(R.id.txtOrderNote)
        txtEmpty = findViewById(R.id.txtEmpty)
        btnSend = findViewById(R.id.btnSend)
        btnDineIn = findViewById(R.id.btnDineIn)
        btnTakeaway = findViewById(R.id.btnTakeaway)
        btnPickup = findViewById(R.id.btnPickup)
        inputTable = findViewById(R.id.inputTable)

        findViewById<TextView>(R.id.txtStoreName).text =
            deviceStore.storeName.ifBlank { "門店" }
        findViewById<TextView>(R.id.txtStoreNo).text =
            deviceStore.storeAccount.ifBlank { deviceStore.storeId.take(8) }

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

        // 左側 rail(共用元件)
        RailNav.bind(this)

        btnDineIn.setOnClickListener { setFulfillment("dine_in") }
        btnTakeaway.setOnClickListener { setFulfillment("takeaway") }
        btnPickup.setOnClickListener { setFulfillment("pickup") }
        setFulfillment("dine_in")

        btnSend.setOnClickListener { sendOrder() }
        findViewById<Button>(R.id.btnSettle).setOnClickListener {
            val o = existingOrder
            if (o != null) {
                startActivity(Intent(this, OrderDetailActivity::class.java).putExtra("orderId", o.id))
            } else if (cart.isNotEmpty()) {
                Toast.makeText(this, "先「下單」送廚房，之後再結帳", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "購物車未有任何菜品", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<TextView>(R.id.btnEditNote).setOnClickListener { editOrderNote() }
        txtOrderNote.setOnClickListener { editOrderNote() }

        // 自動打印開關(存本機)
        autoPrint = prefs.read()?.optBoolean("autoPrint", true) ?: true
        val txtAuto = findViewById<TextView>(R.id.txtAutoPrint)
        fun renderAuto() {
            txtAuto.text = if (autoPrint) "開" else "關"
            txtAuto.setBackgroundResource(if (autoPrint) R.drawable.bg_pill_on else R.drawable.bg_pill_light)
            txtAuto.setTextColor(getColor(if (autoPrint) R.color.card else R.color.orange_600))
            findViewById<TextView>(R.id.txtAutoPrintHint).text =
                if (autoPrint) "落單／結帳自動出單" else "已關閉 · 唔會自動出單"
        }
        renderAuto()
        txtAuto.setOnClickListener {
            autoPrint = !autoPrint
            prefs.write(JSONObject().put("autoPrint", autoPrint))
            renderAuto()
        }

        findViewById<Button>(R.id.btnPrintKitchen).setOnClickListener { printKitchenNow() }
        findViewById<Button>(R.id.btnPrintReceipt).setOnClickListener { printReceiptNow() }

        findViewById<EditText>(R.id.txtSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchKeyword = s?.toString()?.trim().orEmpty()
                if (searchKeyword.isNotEmpty()) {
                    selectedCategory = null
                    renderCategoryChips()
                }
                applyFilter()
            }
        })

        // 加菜模式 / 由桌台帶枱號入嚟
        intent.getStringExtra("tableName")?.takeIf { it.isNotBlank() }?.let {
            inputTable.setText(it)
            inputTable.isEnabled = false
        }
        existingOrderId = intent.getStringExtra("existingOrderId")
        existingOrderId?.let { oid ->
            lifecycleScope.launch {
                existingOrder = withContext(Dispatchers.IO) { orderRepo.byId(oid) }
                existingOrder?.let {
                    inputTable.setText(it.tableName ?: "")
                    txtPayHint.text = "待加菜單號 ${it.localOrderNo}"
                    Toast.makeText(this@OrderActivity, "加菜模式:${it.localOrderNo}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        loadMenu()
    }

    // ---- 堂食 / 外賣 / 自取 ----

    private fun setFulfillment(kind: String) {
        fulfillment = kind
        listOf(
            btnDineIn to "dine_in",
            btnTakeaway to "takeaway",
            btnPickup to "pickup",
        ).forEach { (tv, k) ->
            val on = k == kind
            tv.setBackgroundResource(if (on) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
            tv.setTextColor(getColor(if (on) R.color.card else R.color.slate_700))
        }
        inputTable.visibility = if (kind == "dine_in") View.VISIBLE else View.GONE
    }

    private fun fulfillmentLabel(): String = when (fulfillment) {
        "takeaway" -> "外賣"
        "pickup" -> "自取"
        else -> "堂食"
    }

    // ---- 餐牌 ----

    private fun loadMenu() {
        val storeId = deviceStore.storeId
        lifecycleScope.launch {
            bootstrap = bootstrapRepo.cached(storeId)
            if (bootstrap == null) refreshMenu() else { renderMenu(); refreshMenu() }
        }
    }

    private fun refreshMenu() {
        val storeId = deviceStore.storeId
        lifecycleScope.launch {
            bootstrapRepo.refresh(storeId).onSuccess {
                bootstrap = bootstrapRepo.cached(storeId)
                renderMenu()
            }.onFailure { e ->
                if (bootstrap != null) {
                    Toast.makeText(this@OrderActivity, "離線:用緊本地快取餐牌", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@OrderActivity, "餐牌載入失敗:${e.message}", Toast.LENGTH_LONG).show()
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
        renderCategoryChips()
        applyFilter()
        renderCart()
        refreshSoldOut()
    }

    /** 沽清狀態(本機,同 web `loadSoldOutState()` 同一份語意)。 */
    private fun refreshSoldOut() {
        lifecycleScope.launch {
            soldOut = withContext(Dispatchers.IO) { soldOutStore.all() }
            applyFilter()
        }
    }

    /** 分類 chips(截圖:選中橙底白字,未選灰底 slate-700;h-10 px-4 rounded-full)。 */
    private fun renderCategoryChips() {
        val bar = findViewById<LinearLayout>(R.id.chipsCategory)
        bar.removeAllViews()
        val items: List<Pair<String?, String>> =
            listOf<Pair<String?, String>>(null to "全部") + categories.map { it.first to it.second }
        items.forEach { (catId, label) ->
            val selected = catId == selectedCategory
            val tv = TextView(this).apply {
                text = label
                minHeight = dp(40)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(if (selected) R.color.card else R.color.slate_700))
                background = getDrawable(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
                setOnClickListener {
                    selectedCategory = catId
                    findViewById<EditText>(R.id.txtSearch).setText("")
                    renderCategoryChips()
                    applyFilter()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            bar.addView(tv, lp)
        }
    }

    private fun applyFilter() {
        val base = if (selectedCategory == null) menu else menu.filter { it.categoryId == selectedCategory }
        val list = if (searchKeyword.isBlank()) base
        else base.filter { it.name.contains(searchKeyword, ignoreCase = true) }
        menuAdapter.submit(list, cartQtyMap(), soldOut)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun cartQtyMap(): Map<String, Int> = cart.mapValues { it.value.quantity }

    // ---- 購物車 ----

    private fun addToCart(item: MenuItemLite) {
        val st = soldOut[item.id]
        if (st?.soldOut == true) {
            Toast.makeText(this, "${item.name} 已沽清", Toast.LENGTH_SHORT).show()
            return
        }
        val remaining = st?.remainingQty
        val inCart = cart[item.id]?.quantity ?: 0
        if (remaining != null && inCart >= remaining) {
            Toast.makeText(this, "${item.name} 只剩 $remaining 份", Toast.LENGTH_SHORT).show()
            return
        }
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
        if (newQty <= 0) cart.remove(item.menuItemId) else cart[item.menuItemId] = item.copy(quantity = newQty)
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
        fun money(v: Double) = String.format(Locale.US, "%s %.2f", currency, v)

        txtOrderCount.text = "$count 項"
        txtSubtotal.text = money(totals.subtotal)
        txtCartTotal.text = money(totals.total)
        txtEmpty.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
        txtOrderNote.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        btnSend.isEnabled = count > 0
        txtPayHint.text = when {
            existingOrder != null -> "待加菜單號 ${existingOrder?.localOrderNo}"
            count == 0 -> "點餐結帳在此；線上／線下訂單見螢幕下方"
            else -> "${fulfillmentLabel()} · $count 項，送廚房後可結帳"
        }
        cartAdapter.submit(lines)
        menuAdapter.refreshBadges(cartQtyMap())
    }

    private fun editOrderNote() {
        val input = EditText(this).apply {
            setText(orderNote)
            hint = "例:少辣、分開裝"
        }
        AlertDialog.Builder(this)
            .setTitle("全單備註")
            .setView(input)
            .setPositiveButton("確定") { _, _ ->
                orderNote = input.text.toString().trim()
                txtOrderNote.text = orderNote.ifBlank { "(可選)" }
                txtOrderNote.setTextColor(getColor(if (orderNote.isBlank()) R.color.text_faint else R.color.text_primary))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 打印 ----

    private fun showPrinterDialog() {
        val store = PrinterStore(this)
        val input = EditText(this).apply {
            hint = "打印機 IP(例 192.168.1.100)"
            setText(store.ip)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("打印機設置(LAN raw 9100)")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                store.ip = input.text.toString().trim()
                Toast.makeText(this, if (store.hasPrinter()) "已設定:${store.ip}:${store.port}" else "已清除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 手動打印廚房單(不受自動打印開關影響 —— web 手動優先)。 */
    private fun printKitchenNow() {
        if (cart.isEmpty()) {
            Toast.makeText(this, "購物車未有任何菜品", Toast.LENGTH_SHORT).show()
            return
        }
        val payload = JSONObject()
            .put("id", "preview-" + System.currentTimeMillis())
            .put("tableName", if (fulfillment == "dine_in") inputTable.text.toString().ifBlank { "堂食" } else fulfillmentLabel())
            .put("ticketType", "kitchen")
            .put("items", JSONArray().also { arr ->
                cart.values.forEach {
                    arr.put(JSONObject().put("name", it.name).put("quantity", it.quantity).put("price", it.price))
                }
            })
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (PrinterStore(applicationContext).hasPrinter()) printService.printKitchenTicket(payload)
                    else { printService.renderKitchenBytes(payload); Result.success(Unit) }
                }.getOrElse { Result.failure(it) }
            }
            Toast.makeText(
                this@OrderActivity,
                r.fold({ "廚房單已打印" }, { "打印失敗:${it.message}" }),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun printReceiptNow() {
        val o = existingOrder
        if (o == null) {
            Toast.makeText(this, "尚未有已下單嘅單可以打收據", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { printService.printReceipt(orderRepo.orderRowJson(o), o.paymentMethod ?: "現金", o.total) }
                    .getOrElse { Result.failure(it) }
            }
            Toast.makeText(
                this@OrderActivity,
                r.fold({ "收據已打印" }, { "打印失敗:${it.message}" }),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---- 送單 ----

    private fun sendOrder() {
        val items = cart.values.toList()
        if (items.isEmpty()) return

        btnSend.isEnabled = false
        lifecycleScope.launch {
            try {
                val bs = bootstrap

                // ── 加菜模式 ──
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
                    val addonJob = JSONObject()
                        .put("id", updated.id)
                        .put("tableName", updated.tableName ?: JSONObject.NULL)
                        .put("ticketType", "addon")
                        .put("items", newItemsOnly(updated, itemsJson, items))
                    val printResult = withContext(Dispatchers.IO) {
                        runCatching {
                            if (autoPrint && PrinterStore(applicationContext).hasPrinter()) printService.printKitchenTicket(addonJob)
                            else Result.success(Unit)
                        }.getOrElse { Result.failure(it) }
                    }
                    withContext(Dispatchers.IO) { syncFlusher.flush() }
                    withContext(Dispatchers.Main) {
                        cart.clear()
                        btnSend.isEnabled = true
                        Toast.makeText(
                            this@OrderActivity,
                            "✓ 加菜已落單 ${updated.localOrderNo}" + printResult.fold({ if (autoPrint) " · 已打印" else "" }, { " · ⚠ 打印:${it.message}" }),
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                    return@launch
                }

                // ── 新單 ──
                val tableName = when (fulfillment) {
                    "dine_in" -> inputTable.text.toString().trim().ifBlank { "堂食" }
                    else -> fulfillmentLabel()
                }
                val localNo = orderRepo.nextLocalOrderNo()
                val built = OrderBuilder.build(
                    storeId = deviceStore.storeId,
                    tableName = tableName,
                    fulfillment = fulfillment,
                    items = items,
                    taxRate = bs?.taxRate,
                    serviceChargeRate = bs?.serviceChargeRate,
                    orderNote = orderNote.takeIf { it.isNotBlank() },
                    localOrderNo = localNo,
                )
                orderRepo.createOrder(built).getOrElse { e ->
                    withContext(Dispatchers.Main) {
                        btnSend.isEnabled = true
                        Toast.makeText(this@OrderActivity, "建單失敗:${e.message}", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val printResult = withContext(Dispatchers.IO) {
                    runCatching {
                        if (autoPrint && PrinterStore(applicationContext).hasPrinter()) printService.printKitchenTicket(built.payload)
                        else { printService.renderKitchenBytes(built.payload); Result.success(Unit) }
                    }.getOrElse { Result.failure(it) }
                }
                withContext(Dispatchers.IO) { syncFlusher.flush() }

                withContext(Dispatchers.Main) {
                    cart.clear()
                    renderCart()
                    btnSend.isEnabled = true
                    val msg = buildString {
                        append("✓ ${built.order.localOrderNo} 已收單")
                        printResult.fold(
                            { if (autoPrint) append(" · 廚房單已打印") },
                            { append(" · ⚠ 打印:${it.message}") },
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

    private fun newItemsOnly(order: com.macaupos.nativeapp.data.db.PosOrderEntity, all: JSONArray, added: List<OrderBuilder.Item>): JSONArray {
        val arr = JSONArray()
        added.forEach {
            arr.put(JSONObject().put("name", it.name).put("quantity", it.quantity).put("price", it.price))
        }
        return arr
    }

    // ---- Adapters ----

    private inner class MenuAdapter(
        private val onClick: (MenuItemLite) -> Unit,
    ) : RecyclerView.Adapter<MenuAdapter.VH>() {

        private var items: List<MenuItemLite> = emptyList()
        private var badges: Map<String, Int> = emptyMap()
        private var soldOutMap: Map<String, com.macaupos.nativeapp.data.SoldOutStore.State> = emptyMap()

        fun submit(
            list: List<MenuItemLite>,
            badgeMap: Map<String, Int>,
            soldOutState: Map<String, com.macaupos.nativeapp.data.SoldOutStore.State> = emptyMap(),
        ) {
            items = list
            badges = badgeMap
            soldOutMap = soldOutState
            notifyDataSetChanged()
        }

        fun refreshBadges(badgeMap: Map<String, Int>) {
            badges = badgeMap
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_menu, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.txtCurrency.text = bootstrap?.currency ?: "MOP"
            h.txtName.text = item.name
            h.txtPrice.text = String.format(Locale.US, "%.2f", item.price)
            val qty = badges[item.id] ?: 0
            h.txtBadge.visibility = if (qty > 0) View.VISIBLE else View.GONE
            h.txtBadge.text = "x$qty"
            val st = soldOutMap[item.id]
            val isSoldOut = st?.soldOut == true
            h.txtAdd.text = when {
                isSoldOut -> "售罄"
                st?.remainingQty != null -> "只剩 ${st.remainingQty} 份"
                else -> "加入"
            }
            h.itemView.alpha = if (isSoldOut) 0.55f else 1f
            h.itemView.setOnClickListener { onClick(item) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtName)
            val txtPrice: TextView = v.findViewById(R.id.txtPrice)
            val txtCurrency: TextView = v.findViewById(R.id.txtCurrency)
            val txtAdd: TextView = v.findViewById(R.id.txtAdd)
            val txtBadge: TextView = v.findViewById(R.id.txtBadge)
        }
    }

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
            h.txtSpec.text = item.note?.takeIf { it.isNotBlank() }
                ?.let { "備註:$it · ${String.format(Locale.US, "%s%.2f", currency, item.price)}" }
                ?: String.format(Locale.US, "未選規格 · %s%.2f", currency, item.price)
            h.txtQty.text = item.quantity.toString()
            h.txtAmount.text = String.format(Locale.US, "%s%.2f", currency, item.price * item.quantity)
            h.btnPlus.setOnClickListener { changeQty(item, +1) }
            h.btnMinus.setOnClickListener { changeQty(item, -1) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtLineName)
            val txtSpec: TextView = v.findViewById(R.id.txtLineSpec)
            val txtQty: TextView = v.findViewById(R.id.txtQty)
            val txtAmount: TextView = v.findViewById(R.id.txtLineAmount)
            val btnPlus: TextView = v.findViewById(R.id.btnPlus)
            val btnMinus: TextView = v.findViewById(R.id.btnMinus)
        }
    }
}
