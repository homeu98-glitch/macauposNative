package com.macaupos.nativeapp.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 極簡 JSON 檔案存儲(對齊 macauPosAndroid `DeviceStore` 模式)。
 *
 * P0 階段用嚟放:離線登入憑證、裝置綁定(deviceId/deviceSecret)、UI 偏好。
 * P1 引入 Room 之後,呢個類只保留「細粒度設定」用途;訂單/餐牌等結構化資料行 Room。
 *
 * 寫入係 atomic(temp + rename),避免斷電寫爛。
 */
class JsonStore(context: Context, private val fileName: String) {

    private val file: File = File(context.filesDir, fileName)

    @Synchronized
    fun read(): JSONObject? = runCatching {
        if (!file.exists()) return null
        JSONObject(file.readText(Charsets.UTF_8))
    }.getOrNull()

    @Synchronized
    fun write(obj: JSONObject): Boolean = runCatching {
        val tmp = File(file.parentFile, "$fileName.tmp")
        tmp.writeText(obj.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // Windows 模擬器個別檔案系統 renameTo 對已存在 target 會失敗 → 先刪再試
            file.delete()
            tmp.renameTo(file)
        }
        true
    }.getOrDefault(false)

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }
}
