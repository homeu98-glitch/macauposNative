package com.macaupos.nativeapp

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.repo.PrintJobsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 打印中心 —— 對齊 web `/api/pos/print-jobs`:
 * 睇最近打印任務(已打印 / 失敗 + 錯誤原因),失敗可以逐個重試。
 * server 拉 status 時會順手 void 太舊嘅 stale job,所以「刷新」= 對帳。
 */
class PrintCenterActivity : ComponentActivity() {

    private lateinit var client: PrintJobsClient
    private var jobsAll: List<PrintJobsClient.Job> = emptyList()
    private var jobs: List<PrintJobsClient.Job> = emptyList()
    private lateinit var adapter: Adapter
    private var statusFilter = "all"

    private val TABS = listOf(
        "records" to "打印記錄",
        "receipt-template" to "收據模板",
        "label-template" to "標籤模板",
        "kitchen-template" to "廚房模板",
        "shift-template" to "交班模板",
        "kiosk-template" to "自助點餐機",
    )
    private val STATUS_FILTERS = listOf(
        "all" to "全部", "sent" to "已發送", "printed" to "打印成功",
        "pending" to "待補傳", "failed" to "失敗",
    )
    private var activeTab = "records"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_print_center)
        RailNav.attach(this)
        client = PrintJobsClient(DeviceStore(this))

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { load() }

        adapter = Adapter()
        findViewById<RecyclerView>(R.id.recyclerJobs).apply {
            layoutManager = LinearLayoutManager(this@PrintCenterActivity)
            adapter = this@PrintCenterActivity.adapter
        }

        findViewById<Button>(R.id.btnOpenTemplates).setOnClickListener {
            startActivity(Intent(this, PrintTemplatesActivity::class.java))
        }

        renderTabs()
        renderStatusFilters()
        load()
    }

    private fun renderTabs() {
        val bar = findViewById<android.widget.LinearLayout>(R.id.chipsTabs)
        bar.removeAllViews()
        TABS.forEach { (key, label) ->
            val selected = key == activeTab
            val tv = TextView(this).apply {
                text = label
                minHeight = dp(36)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(if (selected) R.color.card else R.color.slate_700))
                background = getDrawable(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
                setOnClickListener { setTab(key) }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(6)
            bar.addView(tv, lp)
        }
    }

    private fun renderStatusFilters() {
        val bar = findViewById<android.widget.LinearLayout>(R.id.chipsStatus)
        bar.removeAllViews()
        STATUS_FILTERS.forEach { (key, label) ->
            val selected = key == statusFilter
            val tv = TextView(this).apply {
                text = label
                minHeight = dp(32)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(12), dp(5), dp(12), dp(5))
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColor(if (selected) R.color.card else R.color.slate_700))
                background = getDrawable(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
                setOnClickListener {
                    statusFilter = key
                    renderStatusFilters()
                    applyStatusFilter()
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = dp(6)
            bar.addView(tv, lp)
        }
    }

    private fun setTab(key: String) {
        activeTab = key
        renderTabs()
        val isRecords = key == "records"
        findViewById<android.widget.LinearLayout>(R.id.panelRecords).visibility =
            if (isRecords) View.VISIBLE else View.GONE
        findViewById<android.widget.LinearLayout>(R.id.panelTemplates).visibility =
            if (isRecords) View.GONE else View.VISIBLE
        if (!isRecords) {
            findViewById<TextView>(R.id.txtTemplatePlaceholder).text =
                "「$key」模板編輯功能喺原生版以簡化方式提供,完整編輯請用 web 版"
        }
    }

    private fun applyStatusFilter() {
        jobs = when (statusFilter) {
            "sent" -> jobsAll.filter { it.status == "sent" }
            "printed" -> jobsAll.filter { it.status == "printed" }
            "pending" -> jobsAll.filter { it.status == "pending" }
            "failed" -> jobsAll.filter { it.status == "failed" }
            else -> jobsAll
        }
        adapter.notifyDataSetChanged()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun load() {
        findViewById<TextView>(R.id.txtHint).text = "載入中…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { client.list() }
            when (r) {
                is PrintJobsClient.Result.Ok -> {
                    jobsAll = r.jobs
                    applyStatusFilter()
                    val failed = jobsAll.count { it.status == "failed" }
                    findViewById<TextView>(R.id.txtHint).text =
                        if (jobsAll.isEmpty()) "最近冇打印任務"
                        else "共 ${jobsAll.size} 個任務 · 失敗 $failed 個"
                }
                is PrintJobsClient.Result.Offline ->
                    findViewById<TextView>(R.id.txtHint).text = "離線:載入唔到打印任務"
                is PrintJobsClient.Result.Fail ->
                    findViewById<TextView>(R.id.txtHint).text = "載入失敗:${r.error}"
            }
        }
    }

    private fun retry(job: PrintJobsClient.Job) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { client.retry(job.id) }
            r.onSuccess {
                Toast.makeText(this@PrintCenterActivity, "✓ 已重新排隊打印", Toast.LENGTH_SHORT).show()
                load()
            }.onFailure {
                Toast.makeText(this@PrintCenterActivity, "重試失敗:${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_print_job, parent, false))

        override fun getItemCount(): Int = jobs.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val job = jobs[pos]
            h.txtId.text = "#" + job.id.takeLast(8)
            h.txtStatus.text = if (job.status == "printed") "已打印" else "失敗"
            h.txtStatus.setTextColor(
                getColor(if (job.status == "printed") R.color.ok_green else R.color.warn_red)
            )
            h.txtError.text = job.lastError ?: if (job.status == "printed") "—" else "未知原因"
            h.btnRetry.visibility = if (job.status == "failed") View.VISIBLE else View.GONE
            h.btnRetry.setOnClickListener { retry(job) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val txtId: TextView = v.findViewById(R.id.txtJobId)
            val txtStatus: TextView = v.findViewById(R.id.txtJobStatus)
            val txtError: TextView = v.findViewById(R.id.txtJobError)
            val btnRetry: Button = v.findViewById(R.id.btnRetry)
        }
    }
}
