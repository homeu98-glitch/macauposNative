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

    /** 廚房單:order row JSON → PrintJobDto → TicketRenderer(kind="kitchen")→ raw socket。 */
    suspend fun printKitchenTicket(orderJson: JSONObject): Result<Unit> {
        if (!printerStore.hasPrinter()) {
            return Result.failure(IllegalStateException("未設定打印機(主畫面「設置」填 IP)"))
        }
        val bytes = renderKitchenBytes(orderJson)
        return SdkPrinter.printBytes(appContext, printerStore.toCfg(), bytes)
    }

    /** 渲染字節(冇打印機都可以攞嚟自檢/存檔)。 */
    fun renderKitchenBytes(orderJson: JSONObject): ByteArray {
        val job = PrintJobDto.fromJson(orderJson)
        return TicketRenderer.renderTicket(
            job, printerStore.toCfg(), "kitchen",
            deviceStore.storeName.ifBlank { null }, null, null,
        )
    }
}
