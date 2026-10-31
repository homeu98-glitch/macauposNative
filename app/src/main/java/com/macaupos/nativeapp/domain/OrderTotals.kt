package com.macaupos.nativeapp.domain

/**
 * 計價 —— 1:1 移植 web `kiosk-cart.ts` `computeOrderTotals`:
 *
 *     subtotal = Σ(price × qty)      // 浮點,**刻意唔 round**(web 同一語意)
 *     tax      = subtotal × taxRate
 *     service  = subtotal × serviceChargeRate
 *     total    = subtotal + tax + service
 *
 * 🔴 會員價/折扣唔喺呢度處理:行「全單折扣」通道(discountAmount/discountNote),
 *    令收據小計→折扣→總計、報表、交班自動一致(web 2026-10-20 決定)。
 */
object OrderTotals {

    data class Totals(
        val subtotal: Double,
        val taxAmount: Double,
        val serviceChargeAmount: Double,
        val total: Double,
    )

    data class CartLine(val price: Double, val quantity: Int)

    fun compute(items: List<CartLine>, taxRate: Double?, serviceChargeRate: Double?): Totals {
        val subtotal = items.sumOf { (it.price.takeIf { p -> p.isFinite() } ?: 0.0) * (it.quantity.coerceAtLeast(0)) }
        val tax = subtotal * (taxRate ?: 0.0).let { if (it.isFinite()) it else 0.0 }
        val service = subtotal * (serviceChargeRate ?: 0.0).let { if (it.isFinite()) it else 0.0 }
        return Totals(subtotal, tax, service, subtotal + tax + service)
    }
}
