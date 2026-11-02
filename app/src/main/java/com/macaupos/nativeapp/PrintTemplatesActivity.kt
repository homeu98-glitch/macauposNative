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
import com.macaupos.nativeapp.data.repo.PrintTemplatesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 打印模板 —— 對齊 web「打印」頁(模板存 DB,以 store 為鍵):
 *
 * - 由 `GET /api/pos/print-templates?storeId=` 拉(**唔用靜態檔/本地快取做真源**)
 * - 槽位:收據 receipt / 自助機 kiosk / 廚房 kitchen / 標籤 label / 交班 shift
 * - 可改:頁尾文字(footerText)、廚房抬頭(headerText)、收據 QR 網址(qrUrl)
 * - 儲存 = 整份 raw JSON round-trip 回寫,**唔會清走 blocks/order/qrSize**
 */
class PrintTemplatesActivity : ComponentActivity() {

    private lateinit var client: PrintTemplatesClient
    private var templates: JSONObject? = null
    private var slotOrder: List<Pair<String, String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_print_templates)
        client = PrintTemplatesClient(DeviceStore(this))

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { load() }

        findViewById<RecyclerView>(R.id.recyclerTemplates).apply {
            layoutManager = LinearLayoutManager(this@PrintTemplatesActivity)
            adapter = Adapter()
        }
        load()
    }

    private fun load() {
        findViewById<TextView>(R.id.txtHint).text = "載入中…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { client.fetch() }
            when (r) {
                is PrintTemplatesClient.Result.Ok -> {
                    templates = r.snapshot.templates
                    // 本機快取(建 job 時附模板用)
                    com.macaupos.nativeapp.data.repo.TemplateStore(this@PrintTemplatesActivity)
                        .save(r.snapshot.templates)
                    slotOrder = buildSlots(r.snapshot.templates)
                    findViewById<TextView>(R.id.txtHint).text = when {
                        !r.snapshot.found -> "雲端未設定過模板(用預設)"
                        r.snapshot.updatedAt != null -> "已同步 · 更新於 ${r.snapshot.updatedAt?.replace("T", " ")?.take(16)}"
                        else -> "已同步"
                    }
                    findViewById<RecyclerView>(R.id.recyclerTemplates).adapter?.notifyDataSetChanged()
                }
                is PrintTemplatesClient.Result.Offline ->
                    findViewById<TextView>(R.id.txtHint).text = "離線:載入唔到模板"
                is PrintTemplatesClient.Result.Fail ->
                    findViewById<TextView>(R.id.txtHint).text = "載入失敗:${r.error}"
            }
        }
    }

    private fun buildSlots(t: JSONObject?): List<Pair<String, String>> {
        val labels = listOf(
            "receipt" to "收據",
            "kitchen" to "廚房單",
            "label" to "標籤",
            "kiosk" to "自助點餐機",
            "shift" to "交班結算單",
            "retailLabel" to "零售價籤",
        )
        return labels.filter { (key, _) -> t?.optJSONObject(key) != null }
    }

    private fun edit(slotKey: String, label: String) {
        val root = templates ?: return
        val slot = root.optJSONObject(slotKey) ?: return

        val fields = ArrayList<Pair<String, String>>()
        if (slot.has("footerText")) fields.add("footerText" to "頁尾文字")
        if (slot.has("headerText")) fields.add("headerText" to "抬頭文字")
        if (slot.has("qrUrl")) fields.add("qrUrl" to "二維碼網址")

        if (fields.isEmpty()) {
            Toast.makeText(this, "$label 冇可編輯文字欄位", Toast.LENGTH_SHORT).show()
            return
        }

        val names = fields.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("$label · 編輯")
            .setItems(names) { _, which ->
                val (field, fieldLabel) = fields[which]
                val input = EditText(this).apply {
                    setText(slot.optString(field, ""))
                    hint = fieldLabel
                    if (field == "qrUrl") inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
                }
                AlertDialog.Builder(this)
                    .setTitle("$label · $fieldLabel")
                    .setView(input)
                    .setPositiveButton("儲存") { _, _ ->
                        runCatching { slot.put(field, input.text.toString().trim()) }
                        root.put(slotKey, slot)
                        save(root)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    private fun save(root: JSONObject) {
        findViewById<TextView>(R.id.txtHint).text = "儲存中…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { client.save(root) }
            r.onSuccess {
                templates = root
                com.macaupos.nativeapp.data.repo.TemplateStore(this@PrintTemplatesActivity).save(root)
                Toast.makeText(this@PrintTemplatesActivity, "✓ 模板已同步到雲端", Toast.LENGTH_SHORT).show()
                load()
            }.onFailure {
                Toast.makeText(this@PrintTemplatesActivity, "儲存失敗:${it.message}", Toast.LENGTH_LONG).show()
                findViewById<TextView>(R.id.txtHint).text = "儲存失敗"
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_soldout, parent, false))

        override fun getItemCount(): Int = slotOrder.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val (key, label) = slotOrder[pos]
            val slot = templates?.optJSONObject(key)
            h.txtName.text = label
            val summary = buildList {
                slot?.optString("headerText")?.takeIf { it.isNotBlank() }?.let { add("抬頭:$it") }
                slot?.optString("footerText")?.takeIf { it.isNotBlank() }?.let { add("頁尾:$it") }
                slot?.optString("qrUrl")?.takeIf { it.isNotBlank() }?.let { add("QR:已設定") }
            }.joinToString(" · ")
            h.txtPrice.text = summary.ifBlank { "未設定文字" }
            h.txtState.text = "編輯"
            h.itemView.setOnClickListener { edit(key, label) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtItemName)
            val txtPrice: TextView = v.findViewById(R.id.txtItemPrice)
            val txtState: TextView = v.findViewById(R.id.txtState)
        }
    }
}
