package com.macaupos.nativeapp

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.macaupos.nativeapp.BuildConfig
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.db.AppDatabase
import com.macaupos.nativeapp.data.repo.OrderRepository
import com.macaupos.nativeapp.data.repo.SyncFlusher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 同步健康 / 對帳守護 —— 對齊 web `sync-reconcile` 嘅核心:
 *
 * 1. 先 flush outbox(在線就即刻上雲)
 * 2. 拉 `/api/pos/state?ordersOnly=1&fields=id,local_order_no,status`
 * 3. 比對:
 *    · 本機有、雲端冇 → **未上雲**(可補推 ORDER_UPDATED,web reconcile 同一招)
 *    · 雲端有、本機冇 → 提示(唔自動刪,避免誤刪別機單)
 *
 * 唔會靜默修復:每條差異都要用戶撳「補推」。
 */
class SyncHealthActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private lateinit var deviceStore: DeviceStore
    private lateinit var orderRepo: OrderRepository
    private lateinit var flusher: SyncFlusher

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_health)
        db = AppDatabase.get(this)
        deviceStore = DeviceStore(this)
        orderRepo = OrderRepository(this, db)
        flusher = SyncFlusher(this, db)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRun).setOnClickListener { run() }
        refreshStats()
    }

    private fun refreshStats() {
        lifecycleScope.launch {
            val (pending, local) = withContext(Dispatchers.IO) {
                val p = db.syncOutboxDao().pendingCount()
                val l = db.posOrderDao().ordersBetween(deviceStore.storeId, 0L, Long.MAX_VALUE).size
                p to l
            }
            findViewById<TextView>(R.id.txtPending).text = pending.toString()
            findViewById<TextView>(R.id.txtLocal).text = local.toString()
        }
    }

    private fun run() {
        findViewById<TextView>(R.id.txtResult).text = "對帳中…"
        findViewById<LinearLayout>(R.id.listMismatch).removeAllViews()
        findViewById<Button>(R.id.btnRun).isEnabled = false

        lifecycleScope.launch {
            // ① flush
            withContext(Dispatchers.IO) { flusher.flush() }

            // ② 拉雲端 id 清單
            val remote = withContext(Dispatchers.IO) { fetchRemoteIds() }
            if (remote == null) {
                findViewById<TextView>(R.id.txtResult).text = "離線／未授權:拉唔到雲端單據"
                findViewById<Button>(R.id.btnRun).isEnabled = true
                return@launch
            }

            // ③ 比對
            val local = withContext(Dispatchers.IO) {
                db.posOrderDao().ordersBetween(deviceStore.storeId, 0L, Long.MAX_VALUE)
            }
            val localIds = local.map { it.id }.toSet()
            val remoteIds = remote.keys
            val missingOnCloud = local.filter { it.id !in remoteIds }
            val missingLocally = remoteIds.filter { it !in localIds }

            findViewById<TextView>(R.id.txtRemote).text = remoteIds.size.toString()
            refreshStats()

            val sb = StringBuilder()
            sb.append("本機 ").append(localIds.size).append(" 單 · 雲端 ").append(remoteIds.size).append(" 單\n")
            sb.append("未上雲:").append(missingOnCloud.size).append(" · 只存在雲端:").append(missingLocally.size)
            findViewById<TextView>(R.id.txtResult).text = sb.toString()

            val list = findViewById<LinearLayout>(R.id.listMismatch)
            missingOnCloud.take(30).forEach { o ->
                val row = TextView(this@SyncHealthActivity).apply {
                    text = "⚠ ${o.localOrderNo} · ${o.status} · 本機有雲端冇(撳一下補推)"
                    setTextColor(getColor(R.color.warn_amber))
                    textSize = 13f
                    setPadding(24, 24, 24, 24)
                    setBackgroundResource(R.drawable.bg_cart_row)
                    setOnClickListener { requeue(o.id, o.localOrderNo) }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 10
                list.addView(row, lp)
            }
            missingLocally.take(10).forEach { id ->
                val row = TextView(this@SyncHealthActivity).apply {
                    text = "ℹ 雲端有此單,本機冇:$id(可能其他機落嘅單)"
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 12f
                    setPadding(24, 20, 24, 20)
                }
                list.addView(row)
            }
            findViewById<Button>(R.id.btnRun).isEnabled = true
        }
    }

    private fun requeue(orderId: String, orderNo: String) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { orderRepo.requeue(orderId) }
            r.onSuccess {
                withContext(Dispatchers.IO) { flusher.flush() }
                Toast.makeText(this@SyncHealthActivity, "已補推 $orderNo", Toast.LENGTH_SHORT).show()
                run()
            }.onFailure {
                Toast.makeText(this@SyncHealthActivity, "補推失敗:${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** @return id → status;null = 離線或未授權 */
    private fun fetchRemoteIds(): Map<String, String>? {
        val storeId = deviceStore.storeId
        if (storeId.isBlank()) return null
        val req = Request.Builder()
            .url(
                "${BuildConfig.POS_URL}/api/pos/state?storeId=$storeId&ordersOnly=1&limit=500" +
                    "&fields=id,status,local_order_no"
            )
            .apply {
                val t = deviceStore.posDeviceToken
                if (t.isNotBlank()) header("Authorization", "Bearer $t")
            }
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
                if (!obj.optBoolean("ok", false)) return null
                val arr: JSONArray = obj.optJSONArray("orders") ?: JSONArray()
                val map = LinkedHashMap<String, String>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (id.isNotBlank()) map[id] = o.optString("status", "")
                }
                map
            }
        } catch (e: Exception) {
            null
        }
    }
}
