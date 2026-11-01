package com.macaupos.nativeapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.macaupos.nativeapp.BuildConfig
import com.macaupos.nativeapp.data.DeviceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 員工清單 —— 對齊 web `/api/pos/staff`(GET):
 * 欄位 `PosStaffDto{ id, name, staffNo, role(manager|cashier), active, permissions }`。
 *
 * ⚠️ 只做**唯讀**:新增／改權限要用 web 設定頁(店主專屬,POST 需要完整
 * permissions 白名單,放 native 做容易出現兩套權限真源)。
 */
class StaffActivity : ComponentActivity() {

    data class Staff(val id: String, val name: String, val staffNo: String, val role: String, val active: Boolean)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var rows: List<Staff> = emptyList()
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_staff)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { load() }

        adapter = Adapter()
        findViewById<RecyclerView>(R.id.recyclerStaff).apply {
            layoutManager = LinearLayoutManager(this@StaffActivity)
            adapter = this@StaffActivity.adapter
        }
        load()
    }

    private fun load() {
        val deviceStore = DeviceStore(this)
        val storeId = deviceStore.storeId
        if (storeId.isBlank()) {
            findViewById<TextView>(R.id.txtHint).text = "未登入"
            return
        }
        findViewById<TextView>(R.id.txtHint).text = "載入中…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url("${BuildConfig.POS_URL}/api/pos/staff?storeId=$storeId")
                    .apply {
                        val t = deviceStore.posDeviceToken
                        if (t.isNotBlank()) header("Authorization", "Bearer $t")
                    }
                    .get()
                    .build()
                runCatching {
                    http.newCall(req).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        resp.code to JSONObject(text)
                    }
                }.getOrNull()
            }
            if (result == null) {
                findViewById<TextView>(R.id.txtHint).text = "離線:載入唔到員工清單"
                return@launch
            }
            val (code, obj) = result
            if (!obj.optBoolean("ok", false)) {
                findViewById<TextView>(R.id.txtHint).text = "載入失敗:${obj.optString("error", "HTTP $code")}"
                return@launch
            }
            val arr: JSONArray = obj.optJSONArray("staff") ?: JSONArray()
            rows = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    Staff(
                        id = it.optString("id"),
                        name = it.optString("name"),
                        staffNo = it.optString("staffNo", it.optString("staff_no", "")),
                        role = it.optString("role", "cashier"),
                        active = it.optBoolean("active", true),
                    )
                }
            }
            findViewById<TextView>(R.id.txtHint).text =
                "${rows.size} 位店員 · 啟用 ${rows.count { it.active }} 位(新增／改權限請用 web)"
            adapter.notifyDataSetChanged()
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_staff, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = rows[pos]
            h.txtName.text = s.name.ifBlank { "(未命名)" }
            h.txtMeta.text = listOfNotNull(
                s.staffNo.takeIf { it.isNotBlank() }?.let { "編號 $it" },
                if (s.active) "啟用" else "停用",
            ).joinToString(" · ")
            h.txtRole.text = when (s.role) {
                "manager" -> "店長"
                "cashier" -> "收銀"
                else -> s.role
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtName: TextView = v.findViewById(R.id.txtStaffName)
            val txtMeta: TextView = v.findViewById(R.id.txtStaffMeta)
            val txtRole: TextView = v.findViewById(R.id.txtStaffRole)
        }
    }
}
