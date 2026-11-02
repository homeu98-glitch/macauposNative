package com.macaupos.nativeapp

import android.app.Activity
import android.content.Intent
import android.widget.TextView
import android.widget.Toast

/**
 * 左側 rail 導航 —— 對齊 web `AppSidebar`:
 * 全部大功能(點餐/訂單/外送/會員/庫存/報表/打印/交班/設置)一律由 rail 入,
 * 每頁都掛同一條 rail,當前頁高亮。
 *
 * 每頁 layout 用 `<include layout="@layout/view_rail" />`,onCreate 叫 [bind]。
 */
object RailNav {

    /**
     * 喺**任何** Activity 掛上共用 rail(唔使改 layout):
     * 將 setContentView 之後嘅 root 包一層橫向容器,左邊 rail、右邊原本內容。
     * 入面會自動 [bind]。
     */
    fun attach(activity: Activity) {
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        if (content.childCount == 0) return
        val original = content.getChildAt(0) ?: return
        content.removeView(original)

        val row = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val rail = android.view.LayoutInflater.from(activity).inflate(R.layout.view_rail, row, false)
        row.addView(
            rail,
            android.widget.LinearLayout.LayoutParams(
                (68 * activity.resources.displayMetrics.density).toInt(),
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            )
        )
        row.addView(
            original,
            android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        )
        content.addView(row)
        bind(activity)
    }

    /** @param active `order|orders|delivery|member|inventory|report|print|shift|settings` */
    fun bind(activity: Activity) {
        val active = activity.intent.getStringExtra("railActive") ?: ""
        highlight(activity, active)

        click(activity, R.id.railOrder) {
            activity.startActivity(Intent(activity, OrderActivity::class.java).putExtra("railActive", "order"))
        }
        click(activity, R.id.railOrders) {
            activity.startActivity(Intent(activity, TablesActivity::class.java).putExtra("railActive", "orders"))
        }
        click(activity, R.id.railDelivery) {
            activity.startActivity(Intent(activity, DeliveryActivity::class.java).putExtra("railActive", "delivery"))
        }
        click(activity, R.id.railMember) {
            activity.startActivity(Intent(activity, MemberLookupActivity::class.java).putExtra("railActive", "member"))
        }
        click(activity, R.id.railInventory) {
            activity.startActivity(Intent(activity, InventoryActivity::class.java).putExtra("railActive", "inventory"))
        }
        click(activity, R.id.railReport) {
            activity.startActivity(Intent(activity, ReportActivity::class.java).putExtra("railActive", "report"))
        }
        click(activity, R.id.railPrint) {
            activity.startActivity(Intent(activity, PrintCenterActivity::class.java).putExtra("railActive", "print"))
        }
        click(activity, R.id.railShift) {
            activity.startActivity(Intent(activity, ShiftActivity::class.java).putExtra("railActive", "shift"))
        }
        click(activity, R.id.railSettings) {
            activity.startActivity(Intent(activity, PrinterWizardActivity::class.java).putExtra("railActive", "settings"))
        }
        click(activity, R.id.railOnline) {
            Toast.makeText(activity, "在線 · 網絡正常", Toast.LENGTH_SHORT).show()
        }
        click(activity, R.id.railLogout) {
            com.macaupos.nativeapp.data.JsonStore(activity, "offline-credential.json").clear()
            activity.finishAffinity()
            activity.startActivity(Intent(activity, MainActivity::class.java))
        }
    }

    private fun click(activity: Activity, id: Int, block: () -> Unit) {
        activity.findViewById<TextView>(id)?.setOnClickListener { block() }
    }

    private fun highlight(activity: Activity, active: String) {
        val map = mapOf(
            "order" to R.id.railOrder,
            "orders" to R.id.railOrders,
            "delivery" to R.id.railDelivery,
            "member" to R.id.railMember,
            "inventory" to R.id.railInventory,
            "report" to R.id.railReport,
            "print" to R.id.railPrint,
            "shift" to R.id.railShift,
            "settings" to R.id.railSettings,
        )
        map.forEach { (key, id) ->
            val tv = activity.findViewById<TextView>(id) ?: return@forEach
            val on = key == active
            if (on) {
                tv.setBackgroundResource(R.drawable.bg_rail_active)
                tv.setTextColor(activity.getColor(R.color.rail_text_active))
                tv.setTextSize(12f)
            } else {
                tv.background = null
                tv.setTextColor(activity.getColor(R.color.rail_text))
                tv.setTextSize(11f)
            }
        }
    }
}
