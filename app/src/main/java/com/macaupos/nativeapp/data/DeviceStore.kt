package com.macaupos.nativeapp.data

import android.content.Context
import org.json.JSONObject

/**
 * 裝置綁定狀態(deviceId + deviceSecret + storeId)。
 *
 * 對應 web 版 `/api/pos/login/identities` 嘅「裝置模式」入參 —— 已初始化嘅機
 * 唔使再打 8 位店舖帳號。deviceSecret 係機器級授權,只存本機,唔入版控。
 *
 * 寫入時機(P0 之後接):店主喺呢部機完成 owner 登入 + 綁定後,server 回 deviceSecret。
 * P0 先提供讀寫,綁定流程 P0 下半段接 `POST /api/pos/device/register`。
 */
class DeviceStore(context: Context) {

    private val store = JsonStore(context, "device.json")

    var deviceId: String
        get() = store.read()?.optString("deviceId", "") ?: ""
        set(value) = update { it.put("deviceId", value) }

    var deviceSecret: String
        get() = store.read()?.optString("deviceSecret", "") ?: ""
        set(value) = update { it.put("deviceSecret", value) }

    var storeId: String
        get() = store.read()?.optString("storeId", "") ?: ""
        set(value) = update { it.put("storeId", value) }

    var storeName: String
        get() = store.read()?.optString("storeName", "") ?: ""
        set(value) = update { it.put("storeName", value) }

    var storeAccount: String
        get() = store.read()?.optString("storeAccount", "") ?: ""
        set(value) = update { it.put("storeAccount", value) }

    /** 已初始化(有齊 deviceId + deviceSecret)= 裝置模式,唔使打帳號。 */
    fun isBound(): Boolean = deviceId.length >= 8 && deviceSecret.isNotBlank()

    fun clear() = store.clear()

    private inline fun update(block: (JSONObject) -> Unit) {
        val obj = store.read() ?: JSONObject()
        block(obj)
        store.write(obj)
    }
}
