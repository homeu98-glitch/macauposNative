package com.macaupos.nativeapp.data.repo

import android.content.Context
import com.macaupos.nativeapp.data.DeviceStore
import com.macaupos.nativeapp.print.model.PrinterCfgDto
import org.json.JSONObject

/**
 * 打印機設定(P1-A 簡化版:LAN raw socket 9100)。
 * P1-B 接 web 版「打印機精靈」完整配置(usb/bt/lan + 模板)時擴展。
 */
class PrinterStore(context: Context) {

    private val store = com.macaupos.nativeapp.data.JsonStore(context, "printer.json")

    var name: String
        get() = store.read()?.optString("name", "") ?: ""
        set(v) = update { it.put("name", v) }

    var ip: String
        get() = store.read()?.optString("ip", "") ?: ""
        set(v) = update { it.put("ip", v) }

    var port: Int
        get() = store.read()?.optInt("port", 9100) ?: 9100
        set(v) = update { it.put("port", v) }

    var charset: String
        get() = store.read()?.optString("charset", "gb18030") ?: "gb18030"
        set(v) = update { it.put("charset", v) }

    var paperSize: String
        get() = store.read()?.optString("paperSize", "58") ?: "58"
        set(v) = update { it.put("paperSize", v) }

    /** "lan" | "bluetooth"(同 web PrinterCfg.connectionType) */
    var connectionType: String
        get() = store.read()?.optString("connectionType", "lan") ?: "lan"
        set(v) = update { it.put("connectionType", v) }

    /** 藍牙打印機 MAC(connectionType=bluetooth 時用) */
    var btAddress: String
        get() = store.read()?.optString("btAddress", "") ?: ""
        set(v) = update { it.put("btAddress", v) }

    fun hasPrinter(): Boolean =
        if (connectionType == "bluetooth") btAddress.isNotBlank() else ip.isNotBlank()

    /** 轉成打印引擎要嘅 `PrinterCfgDto`。 */
    fun toCfg(): PrinterCfgDto = PrinterCfgDto.fromJson(
        if (connectionType == "bluetooth") {
            JSONObject()
                .put("id", "local-bt")
                .put("name", name.ifBlank { "藍牙打印機" })
                .put("connectionType", "bluetooth")
                .put("bluetoothAddress", btAddress)
                .put("key", btAddress)
                .put("charset", charset)
                .put("paperSize", paperSize)
        } else {
            JSONObject()
                .put("id", "local-lan")
                .put("name", name.ifBlank { "LAN 打印機" })
                .put("connectionType", "lan")
                .put("ipAddress", ip)
                .put("lanPort", port)
                .put("charset", charset)
                .put("paperSize", paperSize)
        }
    )

    private inline fun update(block: (JSONObject) -> Unit) {
        val obj = store.read() ?: JSONObject()
        block(obj)
        store.write(obj)
    }
}
