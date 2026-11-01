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
 * 落單 —— 1:1 對照 web `pos-app.tsx` 三欄工作台:
 *
 *   左 [訂單明細] │ 中 [餐牌:分類 chips + 搜尋 + 菜卡網格] │ 右 [收銀與支付]
 *
 * 菜卡:web `min-h-36 rounded-2xl` 白卡 —— 名 3 行、右下「加入」橙藥丸、
 *      已加顯示右上「xN」橙角標(web 2026-10-14 拍板「唔要 + 鈕」)。
 * 購物車行:web `bg-slate-50 rounded-2xl` + 圓形 - qty + 步進(減到 0 移除)。
 * 收銀欄:小計 / 折扣 / 應收(橙 26sp)+「下單」橙 +「去結帳」slate-900。
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
    private var categories: List<Pair<String, String>> = emptyList()
    private var bootstrap: BootstrapRepository.Bootstrap? = null
    private var selectedCategory: String? = null
    private var searchKeyword = ""
    private var orderNote: String = ""

    private var existingOrderId: String? = null
    private var existingOrder: com.macaupos.nativeapp.data.db.PosOrderEntity? = null

    private lateinit var menuAdapter: MenuAdapter
    private lateinit var cartAdapter: CartAdapter
    private lateinit var txtOrderCount: TextView
    private lateinit var txtSubtotal: TextView
    private lateinit var txtCartTotal: TextView
    private lateinit var txtPayHint: TextView
    private lateinit var txtOrderNote: TextView
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

        txtOrderCount = findViewById(R.id.txtOrderCount)
        txtSubtotal = findViewById(R.id.txtSubtotal)
        txtCartTotal = findViewById(R.id.txtCartTotal)
        txtPayHint = findViewById(R.id.txtPayHint)
        txtOrderNote = findViewById(R.id.txtOrderNote)
        btnSend = findViewById(R.id.btnSend)
        txtSubtitle = findViewById(R.id.txtSubtitle)

        menuAdapter = MenuAdapter { item -> addToCart(item) }
        findViewById<RecyclerView>(R.id.recyclerMenu).apply {
            layoutManager = GridLayoutManager(this@OrderActivity, 3)
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
        findViewById<Button>(R.id.btnSettle).setOnClickListener {
            val o = existingOrder
            if (o != null) {
                startActivity(Intent(this, OrderDetailActivity::class.java).putExtra("orderId", o.id))
            } else {
                Toast.makeText(this, "先「下單」送廚房，之後再結帳", Toast.LENGTH_SHORT).show()
            }
        }
        txtOrderNote.setOnClickListener { editOrderNote() }

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
                    txtPayHint.text = "待加菜單號 ${it.localOrderNo}"
                    Toast.makeText(this@OrderActivity, "加菜模式:${it.localOrderNo}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        loadMenu()
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
        txtSubtitle.text = "全部菜品 · ${menu.size} 項"
        renderCategoryChips()
        applyFilter()
        renderCart()
    }

    /** 分類 chips(web: h-10 rounded-full px-4 text-sm;選中 orange-500 白字)。 */
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
        menuAdapter.submit(list, cartQtyMap())
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun cartQtyMap(): Map<String, Int> = cart.mapValues { it.value.quantity }

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
        fun money(v: Double) = String.format(Locale.US, "%s%.2f", currency, v)

        txtOrderCount.text = "$count 件"
        txtSubtotal.text = money(totals.subtotal)
        txtCartTotal.text = money(totals.total)
        btnSend.isEnabled = count > 0
        txtPayHint.text = when {
            existingOrder != null -> "待加菜單號 ${existingOrder?.localOrderNo}"
            count == 0 -> "目前尚未下單，可繼續加菜或送廚房"
            else -> "購物車 $count 件，送廚房後可結帳"
        }
        cartAdapter.submit(lines)
        // 菜卡右上「xN」角標要跟住更新
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
                txtOrderNote.text = orderNote.ifBlank { "(可選)·點一下編輯" }
                txtOrderNote.setTextColor(getColor(if (orderNote.isBlank()) R.color.text_faint else R.color.text_primary))
            }
            .setNegativeButton("取消", null)
            .show()
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
                            if (PrinterStore(applicationContext).hasPrinter()) printService.printKitchenTicket(addonJob)
                            else Result.success(Unit)
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
                val tableName = findViewById<EditText>(R.id.inputTable).text.toString().trim().ifBlank { "外賣" }
                val fulfillment = if (tableName.contains("外賣", true) || tableName.contains("takeaway", true)) "takeaway" else "dine_in"
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
                        if (PrinterStore(applicationContext).hasPrinter()) printService.printKitchenTicket(built.payload)
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
                        printResult.fold({ append(" · 廚房單已打印") }, { append(" · ⚠ 打印:${it.message}") })
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

        fun submit(list: List<MenuItemLite>, badgeMap: Map<String, Int>) {
            items = list
            badges = badgeMap
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
            val currency = bootstrap?.currency ?: "MOP"
            h.txtName.text = item.name
            h.txtPrice.text = String.format(Locale.US, "%s%.2f", currency, item.price)
            val qty = badges[item.id] ?: 0
            h.txtBadge.visibility = if (qty > 0) View.VISIBLE else View.GONE
            h.txtBadge.text = "x$qty"
            h.itemView.setOnClickListener { onClick(item) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtName)
            val txtPrice: TextView = v.findViewById(R.id.txtPrice)
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
