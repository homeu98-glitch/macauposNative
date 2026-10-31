package com.macaupos.nativeapp.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 對齊 web `kiosk-cart.ts` `computeOrderTotals` 語意:
 * subtotal 不 round(浮點)、total = subtotal + tax + service。
 */
class OrderTotalsTest {

    @Test
    fun `基本計價`() {
        val t = OrderTotals.compute(
            listOf(
                OrderTotals.CartLine(38.0, 2),
                OrderTotals.CartLine(12.5, 1),
            ),
            taxRate = 0.0,
            serviceChargeRate = 0.10,
        )
        assertEquals(88.5, t.subtotal, 1e-9)
        assertEquals(0.0, t.taxAmount, 1e-9)
        assertEquals(8.85, t.serviceChargeAmount, 1e-9)
        assertEquals(97.35, t.total, 1e-9)
    }

    @Test
    fun `冇 rules = 全部零費`() {
        val t = OrderTotals.compute(listOf(OrderTotals.CartLine(10.0, 3)), null, null)
        assertEquals(30.0, t.total, 1e-9)
        assertEquals(0.0, t.taxAmount, 1e-9)
        assertEquals(0.0, t.serviceChargeAmount, 1e-9)
    }

    @Test
    fun `稅同服務費並存`() {
        val t = OrderTotals.compute(
            listOf(OrderTotals.CartLine(100.0, 1)),
            taxRate = 0.05,
            serviceChargeRate = 0.10,
        )
        assertEquals(5.0, t.taxAmount, 1e-9)
        assertEquals(10.0, t.serviceChargeAmount, 1e-9)
        assertEquals(115.0, t.total, 1e-9)
    }

    @Test
    fun `非有限數值防守 - 唔好 NaN 汙染全單`() {
        val t = OrderTotals.compute(
            listOf(OrderTotals.CartLine(Double.NaN, 1), OrderTotals.CartLine(10.0, 1)),
            taxRate = Double.NaN,
            serviceChargeRate = 0.0,
        )
        assertEquals(10.0, t.subtotal, 1e-9)
        assertEquals(10.0, t.total, 1e-9)
    }

    @Test
    fun `空購物車 = 全零`() {
        val t = OrderTotals.compute(emptyList(), 0.1, 0.1)
        assertEquals(0.0, t.subtotal, 1e-9)
        assertEquals(0.0, t.total, 1e-9)
    }
}
