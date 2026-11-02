package com.macaupos.nativeapp

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.data.JsonStore

/**
 * 左側 rail 導航 —— 1:1 對齊 web `AppSidebar`:
 *
 * 9 個模組(順序=web module-catalog 單一真源):
 *   點餐(order) → 按 workbench mode 分流:堂食=桌台總覽 / 快餐=點餐工作台
 *   訂單(orders) → 訂單列表 OrdersActivity(/orders)
 *   外送 / 會員 / 打印 / 報表 / 沽清 / 交班 / 庫存
 * 底部:店名卡 + 在線徽章 + 登出。
 *
 * 每頁用 `<include layout="@layout/view_rail" />`,onCreate 叫 [bind]。
 */
object RailNav {

    /**
     * 喺**任何** Activity 掛上共用 rail(唔使改 layout):
     * 將 setContentView 之後嘅 root 包一層橫向容器,左邊 rail、右邊原本內容。
     * 入面會自動 [bind]。
     */
    fun attach(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.childCount == 0) return
        val original = content.getChildAt(0) ?: return
        content.removeView(original)

        val row = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val rail = android.view.LayoutInflater.from(activity).inflate(R.layout.view_rail, row, false)
        row.addView(
            rail,
            android.widget.LinearLayout.LayoutParams(
                (72 * activity.resources.displayMetrics.density).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )
        row.addView(
            original,
            android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        content.addView(row)
        bind(activity)
    }

    /** @param active `order|orders|delivery|member|print|report|soldout|shift|inventory` */
    fun bind(activity: Activity) {
        val active = activity.intent.getStringExtra("railActive") ?: ""
        highlight(activity, active)

        // 店名卡(底部)
        val store = DeviceStore(activity)
        activity.findViewById<TextView>(R.id.railStoreName)?.text =
            store.storeName.ifBlank { "門店" }

        // ── 點餐:按 workbench mode 分流 ──
        click(activity, R.id.railOrder) {
            val mode = JsonStore(activity, "workbench.json").read()?.optString("mode", "") ?: ""
            if (mode == "quick") {
                activity.startActivity(Intent(activity, OrderActivity::class.java).putExtra("railActive", "order"))
            } else {
                activity.startActivity(Intent(activity, TablesActivity::class.java).putExtra("railActive", "order"))
            }
        }
        // ── 訂單:訂單列表(/orders),唔係桌台 ──
        click(activity, R.id.railOrders) {
            activity.startActivity(Intent(activity, OrdersActivity::class.java).putExtra("railActive", "orders"))
        }
        click(activity, R.id.railDelivery) {
            activity.startActivity(Intent(activity, DeliveryActivity::class.java).putExtra("railActive", "delivery"))
        }
        click(activity, R.id.railMember) {
            activity.startActivity(Intent(activity, MemberLookupActivity::class.java).putExtra("railActive", "member"))
        }
        click(activity, R.id.railPrint) {
            activity.startActivity(Intent(activity, PrintCenterActivity::class.java).putExtra("railActive", "print"))
        }
        click(activity, R.id.railReport) {
            activity.startActivity(Intent(activity, ReportActivity::class.java).putExtra("railActive", "report"))
        }
        click(activity, R.id.railSoldout) {
            activity.startActivity(Intent(activity, SoldOutActivity::class.java).putExtra("railActive", "soldout"))
        }
        click(activity, R.id.railShift) {
            activity.startActivity(Intent(activity, ShiftActivity::class.java).putExtra("railActive", "shift"))
        }
        click(activity, R.id.railInventory) {
            activity.startActivity(Intent(activity, InventoryActivity::class.java).putExtra("railActive", "inventory"))
        }
        click(activity, R.id.railOnline) {
            Toast.makeText(activity, "在線 · 網絡正常", Toast.LENGTH_SHORT).show()
        }
        click(activity, R.id.railSettings) {
            activity.startActivity(Intent(activity, SettingsActivity::class.java).putExtra("railActive", "settings"))
        }
        click(activity, R.id.railLogout) {
            JsonStore(activity, "offline-credential.json").clear()
            activity.finishAffinity()
            activity.startActivity(Intent(activity, MainActivity::class.java))
        }
    }

    private fun click(activity: Activity, id: Int, block: () -> Unit) {
        activity.findViewById<View>(id)?.setOnClickListener { block() }
    }

    private fun highlight(activity: Activity, active: String) {
        val map = mapOf(
            "order" to R.id.railOrder,
            "orders" to R.id.railOrders,
            "delivery" to R.id.railDelivery,
            "member" to R.id.railMember,
            "print" to R.id.railPrint,
            "report" to R.id.railReport,
            "soldout" to R.id.railSoldout,
            "shift" to R.id.railShift,
            "inventory" to R.id.railInventory,
            "settings" to R.id.railSettings,
        )
        map.forEach { (key, id) ->
            val view = activity.findViewById<View>(id) ?: return@forEach
            val on = key == active
            view.setBackgroundResource(if (on) R.drawable.bg_rail_active else R.drawable.bg_rail_item)
            // 子 TextView 文字色:選中白、未選 slate-200
            recolor(view, if (on) R.color.rail_text_active else R.color.rail_text)
        }
    }

    private fun recolor(v: View, colorRes: Int) {
        if (v is TextView) v.setTextColor(v.context.getColor(colorRes))
        if (v is ViewGroup) for (i in 0 until v.childCount) recolor(v.getChildAt(i), colorRes)
    }
}
