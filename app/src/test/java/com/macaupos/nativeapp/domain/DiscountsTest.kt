package com.macaupos.nativeapp.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 折扣/抹零口徑:只減不增、唔可以負、抹零計入 rounding 而唔係 discount。 */
class DiscountsTest {

    @Test
    fun `冇折扣 = 原價`() {
        val r = Discounts.apply(100.0, null)
        assertEquals(0.0, r.discount, 0.001)
        assertEquals(100.0, r.payable, 0.001)
    }

    @Test
    fun `九折 = 減 10%`() {
        val r = Discounts.apply(100.0, Discounts.Mode.Percent(10.0))
        assertEquals(10.0, r.discount, 0.001)
        assertEquals(90.0, r.payable, 0.001)
    }

    @Test
    fun `固定金額折扣唔可以超過應收`() {
        val r = Discounts.apply(50.0, Discounts.Mode.Amount(80.0))
        assertEquals(50.0, r.discount, 0.001)
        assertEquals(0.0, r.payable, 0.001)
    }

    @Test
    fun `抹零到整數`() {
        val r = Discounts.apply(88.6, Discounts.Mode.RoundDown(1.0))
        assertEquals(88.0, r.payable, 0.001)
        assertEquals(0.6, r.rounding, 0.001)
        assertEquals(0.0, r.discount, 0.001)
    }

    @Test
    fun `抹零到角`() {
        val r = Discounts.apply(88.68, Discounts.Mode.RoundDown(0.1))
        assertEquals(88.6, r.payable, 0.001)
        assertEquals(0.08, r.rounding, 0.001)
    }

    @Test
    fun `百分比夾在 0 到 100`() {
        val over = Discounts.apply(100.0, Discounts.Mode.Percent(150.0))
        assertEquals(100.0, over.discount, 0.001)
        assertEquals(0.0, over.payable, 0.001)
        val negative = Discounts.apply(100.0, Discounts.Mode.Percent(-20.0))
        assertEquals(0.0, negative.discount, 0.001)
        assertEquals(100.0, negative.payable, 0.001)
    }

    @Test
    fun `微細金額四捨五入到分`() {
        val r = Discounts.apply(0.1 + 0.2, Discounts.Mode.Percent(33.0))
        // 0.3 × 33% = 0.099 → 0.10
        assertEquals(0.1, r.discount, 0.001)
        assertEquals(0.2, r.payable, 0.001)
    }
}
