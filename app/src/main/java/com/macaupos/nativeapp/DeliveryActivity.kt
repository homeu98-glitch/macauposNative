package com.macaupos.nativeapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.repo.DeliveryClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 外送看板 —— 對齊 web 外送 tab:
 *
 * - **手動拉取**(J 2026-10-31:零 polling、唔加數據用量)
 * - 三組:待接單(new/queued/draft)、已接單(accepted)、配送中(picked_up)
 * - 唯讀:狀態由 grabber / provider 寫入,web 亦冇前端改狀態 route
 * - `pos_delivery_jobs` 未跑 migration → `tableReady:false`,顯示提示而唔係報錯
 */
class DeliveryActivity : ComponentActivity() {

    private lateinit var client: DeliveryClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_delivery)
        client = DeliveryClient(DeviceStore(this))

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { load() }
        load()
    }

    private fun load() {
        findViewById<TextView>(R.id.txtHint).text = "載入中…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { client.board() }
            when (r) {
                is DeliveryClient.Result.Ok -> {
                    val jobs = r.board.jobs
                    if (!r.board.tableReady) {
                        findViewById<TextView>(R.id.txtHint).text = "外送資料表未建立(請先跑 migration 0076)"
                    } else {
                        findViewById<TextView>(R.id.txtHint).text =
                            "進行中 ${jobs.size} 單 · 手動刷新(同 web 一樣零 polling)"
                    }
                    render(jobs)
                }
                is DeliveryClient.Result.Offline ->
                    findViewById<TextView>(R.id.txtHint).text = "離線:載入唔到外送單"
                is DeliveryClient.Result.Fail ->
                    findViewById<TextView>(R.id.txtHint).text = "載入失敗:${r.error}"
            }
        }
    }

    private fun render(jobs: List<DeliveryClient.Job>) {
        val newCol = findViewById<LinearLayout>(R.id.colNew)
        val acceptedCol = findViewById<LinearLayout>(R.id.colAccepted)
        val pickedCol = findViewById<LinearLayout>(R.id.colPickedUp)
        listOf(newCol, acceptedCol, pickedCol).forEach { it.removeAllViews() }

        fun add(col: LinearLayout, job: DeliveryClient.Job) {
            val card = LayoutInflater.from(this).inflate(R.layout.item_delivery_job, col, false)
            card.findViewById<TextView>(R.id.txtJobNo).text = job.orderNo
            card.findViewById<TextView>(R.id.txtJobStatus).text = statusLabel(job.status)
            card.findViewById<TextView>(R.id.txtJobPlatform).text = listOfNotNull(
                job.platform?.let { "平台:$it" },
                job.provider?.let { "外送員:$it" },
                job.deliveryMode,
            ).joinToString(" · ").ifBlank { "本店自送" }
            card.findViewById<TextView>(R.id.txtJobCustomer).text = listOfNotNull(
                job.customerName,
                job.customerPhone,
            ).joinToString(" · ").ifBlank { "客戶資料未提供" }
            card.findViewById<TextView>(R.id.txtJobAddress).text =
                job.customerAddress ?: job.note ?: "—"
            col.addView(card)
        }

        jobs.forEach { job ->
            when (job.status) {
                "accepted" -> add(acceptedCol, job)
                "picked_up" -> add(pickedCol, job)
                else -> add(newCol, job)
            }
        }
        listOf(newCol, acceptedCol, pickedCol).forEach { col ->
            if (col.childCount == 0) {
                val empty = TextView(this).apply {
                    text = "冇單"
                    setTextColor(getColor(R.color.text_faint))
                    textSize = 12f
                }
                col.addView(empty)
            }
        }
    }

    private fun statusLabel(status: String) = when (status) {
        "draft" -> "草稿"
        "queued" -> "排隊中"
        "new" -> "新單"
        "accepted" -> "已接"
        "picked_up" -> "配送中"
        "delivered" -> "已送達"
        "cancelled" -> "已取消"
        else -> status
    }
}
