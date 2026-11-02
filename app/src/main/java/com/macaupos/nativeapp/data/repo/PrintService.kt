package com.macaupos.nativeapp.data.repo

import android.content.Context
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.print.model.PrintJobDto
import com.macaupos.nativeapp.print.net.SdkPrinter
import com.macaupos.nativeapp.print.net.TicketRenderer
import org.json.JSONObject

/**
 * 打印服務 —— 行 macauPosAndroid 已驗證嘅 `SdkPrinter.printBytes`:
 * LAN → raw socket 9100(5s timeout);藍牙/USB P1-B 接精靈後開放。
 * bytes 由 `TicketRenderer` 渲染(同 web 版同一套字節)。
 */
class PrintService(context: Context) {

    private val appContext: Context = context.applicationContext
    private val printerStore = PrinterStore(appContext)
    private val deviceStore = DeviceStore(appContext)
    private val templates = TemplateStore(appContext)

    /** 每行格數:58mm→32、80mm→48(同 web buildSnapshot 一致;label 機走 TSPL 唔關事)。 */
    private fun cols(): Int = when (printerStore.paperSize) {
        "80" -> 48
        else -> 32
    }

    /** 廚房單:order row JSON → PrintJobDto(附模板)→ TicketRenderer。 */
    suspend fun printKitchenTicket(orderJson: JSONObject): Result<Unit> {
        if (!printerStore.hasPrinter()) {
            return Result.failure(IllegalStateException("未設定打印機(主畫面「設置」填 IP)"))
        }
        val bytes = renderKitchenBytes(orderJson)
        return SdkPrinter.printBytes(appContext, printerStore.toCfg(), bytes)
    }

    /** 收據:結帳後出(kind="receipt",帶付款方式/總額);有模板就跟模板排版。 */
    suspend fun printReceipt(orderJson: JSONObject, paymentMethod: String, total: Double): Result<Unit> {
        if (!printerStore.hasPrinter()) {
            return Result.failure(IllegalStateException("未設定打印機(主畫面「設置」填 IP)"))
        }
        val base = PrintJobDto.fromJson(orderJson)
        val job = base.copy(template = base.template ?: templates.receipt(cols()))
        val bytes = TicketRenderer.renderTicket(
            job, printerStore.toCfg(), "receipt",
            deviceStore.storeName.ifBlank { null }, paymentMethod, total,
        )
        return SdkPrinter.printBytes(appContext, printerStore.toCfg(), bytes)
    }

    /** 渲染字節(冇打印機都可以攞嚟自檢/存檔)。 */
    fun renderKitchenBytes(orderJson: JSONObject): ByteArray {
        val base = PrintJobDto.fromJson(orderJson)
        // 廚房模板:有就用,冇就預設排版(TicketRenderer 會 fall through renderKitchenTicket)
        val job = base.copy(template = base.template ?: templates.kitchen(cols()))
        return TicketRenderer.renderTicket(
            job, printerStore.toCfg(), "kitchen",
            deviceStore.storeName.ifBlank { null }, null, null,
        )
    }
}
