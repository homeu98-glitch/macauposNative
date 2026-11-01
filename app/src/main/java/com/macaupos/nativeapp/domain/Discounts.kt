package com.macaupos.nativeapp.domain

import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * 折扣與抹零 —— 純邏輯(可單測),對齊 web 結帳口徑:
 *
 * - 折扣只可以**減少**應收,唔可以變負數;折扣上限 = 目前應收
 * - 支援三種:百分比(9 折 = 10% off)、固定金額、抹零(向下取整到指定單位)
 * - 一律 round2 之後再套用,避免浮點尾數
 * - 應收最低 0(唔會出現負數找續)
 */
object Discounts {

    sealed class Mode {
        /** 百分比折扣:percentOff = 10 → 九折(減 10%) */
        data class Percent(val percentOff: Double) : Mode()
        /** 固定金額折扣 */
        data class Amount(val amount: Double) : Mode()
        /** 抹零:unit = 1.0(抹到整數)/ 0.1(抹到角) */
        data class RoundDown(val unit: Double) : Mode()
    }

    data class Applied(val discount: Double, val rounding: Double, val payable: Double)

    fun apply(base: Double, mode: Mode?): Applied {
        val safeBase = if (base.isFinite() && base > 0) ReportStats.round2(base) else 0.0
        if (safeBase == 0.0) return Applied(0.0, 0.0, 0.0)

        return when (mode) {
            null -> Applied(0.0, 0.0, safeBase)

            is Mode.Percent -> {
                val pct = mode.percentOff.coerceIn(0.0, 100.0)
                val discount = ReportStats.round2(safeBase * pct / 100.0).coerceAtMost(safeBase)
                Applied(discount, 0.0, ReportStats.round2(safeBase - discount))
            }

            is Mode.Amount -> {
                val discount = ReportStats.round2(mode.amount.coerceAtLeast(0.0)).coerceAtMost(safeBase)
                Applied(discount, 0.0, ReportStats.round2(safeBase - discount))
            }

            is Mode.RoundDown -> {
                val unit = mode.unit.takeIf { it > 0 && it.isFinite() } ?: 1.0
                // 加 1e-9 抵銷浮點誤差(88.68/0.1 可能算出 885.9999…)
                val payable = ReportStats.round2(
                    floor(safeBase / unit + 1e-9) * unit
                ).coerceAtLeast(0.0)
                val cut = ReportStats.round2(safeBase - payable)
                // 抹零金額放 rounding(web 收據分開顯示「抹零」),唔算折扣
                Applied(0.0, cut, payable)
            }
        }
    }
}
