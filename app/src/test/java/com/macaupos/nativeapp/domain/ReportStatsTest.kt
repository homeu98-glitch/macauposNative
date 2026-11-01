package com.macaupos.nativeapp.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 日報表統計口徑(對齊 web 報表:只計 settled、客單價、支付分佈、菜品排行)。 */
class ReportStatsTest {

    private fun order(total: Double, method: String?, status: String = "settled", lines: List<ReportStats.OrderLine> = emptyList()) =
        ReportStats.OrderInput(total, method, status, lines)

    @Test
    fun `只計已結帳單`() {
        val stats = ReportStats.compute(
            listOf(
                order(100.0, "現金"),
                order(50.0, "微信"),
                order(30.0, "現金", status = "sent_to_kitchen"),
            )
        )
        assertEquals(2, stats.orderCount)
        assertEquals(150.0, stats.revenue, 0.001)
    }

    @Test
    fun `客單價 = 營業額除單數`() {
        val stats = ReportStats.compute(listOf(order(100.0, "現金"), order(51.0, "現金")))
        assertEquals(75.5, stats.avgTicket, 0.001)
    }

    @Test
    fun `冇單時全部為零`() {
        val stats = ReportStats.compute(emptyList())
        assertEquals(0, stats.orderCount)
        assertEquals(0.0, stats.revenue, 0.001)
        assertEquals(0.0, stats.avgTicket, 0.001)
        assertEquals(0, stats.topItems.size)
    }

    @Test
    fun `支付方式空白當現金_並按金額排序`() {
        val stats = ReportStats.compute(
            listOf(
                order(10.0, null),
                order(100.0, "微信"),
                order(5.0, ""),
            )
        )
        assertEquals(listOf("微信", "現金"), stats.byPayment.keys.toList())
        assertEquals(15.0, stats.byPayment["現金"] ?: 0.0, 0.001)
        assertEquals(100.0, stats.byPayment["微信"] ?: 0.0, 0.001)
    }

    @Test
    fun `菜品排行_同名合併_按數量再按金額排序`() {
        val stats = ReportStats.compute(
            listOf(
                order(100.0, "現金", lines = listOf(
                    ReportStats.OrderLine("牛肉麵", 2, 60.0),
                    ReportStats.OrderLine("豆漿", 3, 15.0),
                )),
                order(50.0, "現金", lines = listOf(
                    ReportStats.OrderLine("牛肉麵", 1, 30.0),
                    ReportStats.OrderLine("小菜", 1, 20.0),
                )),
            )
        )
        val top = stats.topItems
        assertEquals("牛肉麵", top[0].name)      // 3 份、金額 90(數量相同按金額 desc)
        assertEquals("豆漿", top[1].name)        // 3 份、金額 15
        assertEquals(3, top[0].quantity)
        assertEquals(90.0, top[0].amount, 0.001)
    }

    @Test
    fun `浮點尾數四捨五入到兩位`() {
        val stats = ReportStats.compute(listOf(order(0.1, "現金"), order(0.2, "現金")))
        assertEquals(0.3, stats.revenue, 0.0001)
    }
}
