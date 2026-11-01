package com.macaupos.nativeapp.domain

import kotlin.math.roundToLong

/**
 * 日報表統計 —— 純邏輯(可單測),對齊 web 報表口徑:
 *
 * - 只計 **已結帳**(status=settled)訂單;營業額 = Σ total
 * - 客單價 = 營業額 / 單數(0 單時 = 0)
 * - 支付方式分佈:paymentMethod 空白當「現金」
 * - 菜品排行:按數量 desc,數量相同按金額 desc;同名菜品合併
 *
 * ⚠️ 金額一律四捨五入到 2 位(避免 0.1+0.2 類浮點尾數顯示成 0.30000000000000004)。
 */
object ReportStats {

    data class OrderLine(val name: String, val quantity: Int, val amount: Double)

    /** 一張單的最小輸入(唔直接依賴 Room entity,方便單測)。 */
    data class OrderInput(
        val total: Double,
        val paymentMethod: String?,
        val status: String,
        val lines: List<OrderLine> = emptyList(),
    )

    data class ItemStat(val name: String, val quantity: Int, val amount: Double)

    data class DayStats(
        val revenue: Double,
        val orderCount: Int,
        val avgTicket: Double,
        val byPayment: Map<String, Double>,
        val topItems: List<ItemStat>,
    )

    fun compute(orders: List<OrderInput>): DayStats {
        val settled = orders.filter { it.status == "settled" }
        val revenue = round2(settled.sumOf { it.total.takeIf { v -> v.isFinite() } ?: 0.0 })
        val count = settled.size
        val avg = if (count == 0) 0.0 else round2(revenue / count)

        val byPayment = LinkedHashMap<String, Double>()
        settled.forEach { o ->
            val method = o.paymentMethod?.trim()?.ifBlank { null } ?: "現金"
            byPayment[method] = round2((byPayment[method] ?: 0.0) + o.total)
        }
        // 金額大嘅排前面(同 web 報表一致:一眼睇主要收款方式)
        val paymentSorted = byPayment.entries.sortedByDescending { it.value }
            .associate { it.key to it.value }

        val agg = LinkedHashMap<String, ItemStat>()
        settled.forEach { order ->
            order.lines.forEach { line ->
                if (line.quantity <= 0) return@forEach
                val prev = agg[line.name]
                agg[line.name] = ItemStat(
                    name = line.name,
                    quantity = (prev?.quantity ?: 0) + line.quantity,
                    amount = round2((prev?.amount ?: 0.0) + line.amount),
                )
            }
        }
        val top = agg.values
            .sortedWith(compareByDescending<ItemStat> { it.quantity }.thenByDescending { it.amount })
            .take(20)

        return DayStats(revenue, count, avg, paymentSorted, top)
    }

    fun round2(v: Double): Double {
        if (!v.isFinite()) return 0.0
        return (v * 100).roundToLong() / 100.0
    }
}
