package com.macaupos.nativeapp.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 案例對齊 web `daily-order-seq.test.ts`(prefix="訂單",todayKey="2026-09-10")。
 */
class DailyOrderSeqTest {

    private val prefix = "訂單"
    private val today = "2026-09-10"
    private fun ts(dateKey: String): Long {
        val cal = java.util.Calendar.getInstance()
        val (y, m, d) = dateKey.split("-").map { it.toInt() }
        cal.set(y, m - 1, d, 12, 0, 0)
        return cal.timeInMillis
    }

    @Test
    fun `空清單 = 下一個係1`() {
        assertEquals(0, DailyOrderSeq.maxSeq(emptyList(), prefix, today))
        assertEquals(1, DailyOrderSeq.nextSeq(emptyList(), prefix, today))
    }

    @Test
    fun `當日最大+1`() {
        val orders = listOf(
            "訂單1" to ts(today),
            "訂單2" to ts(today),
        )
        assertEquals(2, DailyOrderSeq.maxSeq(orders, prefix, today))
        assertEquals(3, DailyOrderSeq.nextSeq(orders, prefix, today))
    }

    @Test
    fun `個位數跳位 - 9 之後係10`() {
        val orders = listOf("訂單9" to ts(today))
        assertEquals(9, DailyOrderSeq.maxSeq(orders, prefix, today))
        assertEquals(10, DailyOrderSeq.nextSeq(orders, prefix, today))
    }

    @Test
    fun `唔係今日嘅單唔計`() {
        val orders = listOf("訂單5" to ts("2026-09-09"))
        assertEquals(0, DailyOrderSeq.maxSeq(orders, prefix, today))
    }

    @Test
    fun `唔同前綴唔計`() {
        val orders = listOf("外賣3" to ts(today))
        assertEquals(0, DailyOrderSeq.maxSeq(orders, prefix, today))
    }

    @Test
    fun `壞時間戳保守計入`() {
        // epoch<=0 = 解析唔到 → 保守計入,避免撞號(web 同款)
        val orders = listOf("訂單7" to 0L)
        assertEquals(7, DailyOrderSeq.maxSeq(orders, prefix, today))
    }

    @Test
    fun `唔係純數字尾嘅單號唔計`() {
        val orders = listOf("訂單A1" to ts(today), "訂單" to ts(today))
        assertEquals(0, DailyOrderSeq.maxSeq(orders, prefix, today))
    }

    @Test
    fun `今日key係當地yyyy-MM-dd`() {
        val key = DailyOrderSeq.dateKeyOf(System.currentTimeMillis())
        assertEquals(10, key.length)
        assertEquals('-', key[4])
        assertEquals('-', key[7])
    }
}
