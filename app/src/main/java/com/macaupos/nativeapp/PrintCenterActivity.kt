package com.macaupos.nativeapp

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
    private var jobs: List<PrintJobsClient.Job> = emptyList()
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_print_center)
        client = PrintJobsClient(DeviceStore(this))

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { load() }

        adapter = Adapter()
        findViewById<RecyclerView>(R.id.recyclerJobs).apply {
            layoutManager = LinearLayoutManager(this@PrintCenterActivity)
            adapter = this@PrintCenterActivity.adapter
        }
        load()
    }

    private fun load() {
        findViewById<TextView>(R.id.txtHint).text = "載入中…"
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { client.list() }
            when (r) {
                is PrintJobsClient.Result.Ok -> {
                    jobs = r.jobs
                    val failed = jobs.count { it.status == "failed" }
                    findViewById<TextView>(R.id.txtHint).text =
                        if (jobs.isEmpty()) "最近冇打印任務"
                        else "共 ${jobs.size} 個任務 · 失敗 $failed 個"
                    adapter.notifyDataSetChanged()
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
