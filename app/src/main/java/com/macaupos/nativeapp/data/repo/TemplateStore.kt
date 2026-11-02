package com.macaupos.nativeapp.data.repo

import android.content.Context
import com.macaupos.nativeapp.data.JsonStore
import com.macaupos.nativeapp.print.model.PrintJobDto
import org.json.JSONArray
import org.json.JSONObject

/**
 * 打印模板本機快取 —— 由 `GET /api/pos/print-templates` 拉落嚟之後存,
 * 建打印 job 時轉成 `PrintJobDto.TemplateDto` 交俾 `EscPosRenderer.renderTemplateTicket`。
 *
 * 🔴 轉換規則(對齊 web `EscPosTemplateSnapshot`):
 *   web 模板 = `{ blocks: {sectionId: {visible,size,bold,align,subSize,layout}}, order: [sectionId] }`
 *   native `TemplateDto` = `{ kind, blocks: [Block(id,visible,size,...)], cols }`
 *   ⇒ 按 `order` 逐個 id 由 `blocks` map 抽屬性;`cols` 由紙寬(58→32 / 80→48)計。
 *
 * ⚠️ renderer 只認得特定區塊 id(items/divider/total...)。如果模板根本冇 `items`,
 *    或者轉換失敗,就**唔附模板**,照舊用預設排版(寧願格式舊,唔好唔出紙)。
 */
class TemplateStore(context: Context) {

    private val store = JsonStore(context, "print-templates.json")

    fun save(templates: JSONObject?) {
        if (templates == null) return
        store.write(JSONObject().put("templates", templates))
    }

    fun raw(): JSONObject? = store.read()?.optJSONObject("templates")

    /** 收據模板(沒有/不完整 → null) */
    fun receipt(cols: Int): PrintJobDto.TemplateDto? = build("receipt", cols)

    /** 廚房單模板(沒有/不完整 → null) */
    fun kitchen(cols: Int): PrintJobDto.TemplateDto? = build("kitchen", cols)

    private fun build(slot: String, cols: Int): PrintJobDto.TemplateDto? {
        val root = raw() ?: return null
        val tpl = root.optJSONObject(slot) ?: return null
        val blocksMap = tpl.optJSONObject("blocks") ?: return null
        val order: JSONArray = tpl.optJSONArray("order") ?: return null

        val list = ArrayList<PrintJobDto.Block>(order.length())
        for (i in 0 until order.length()) {
            val id = order.optString(i, "")
            if (id.isBlank()) continue
            val b = blocksMap.optJSONObject(id) ?: continue
            list.add(
                PrintJobDto.Block(
                    id = id,
                    visible = b.optBoolean("visible", true),
                    size = b.optString("size", "s"),
                    bold = b.optBoolean("bold", false),
                    align = b.optString("align", "left"),
                    subSize = b.optString("subSize", "s"),
                    layout = b.optString("layout", "card"),
                )
            )
        }
        // renderer 一定要有 items 區塊先砌得出內容
        if (list.none { it.id == "items" }) return null
        return PrintJobDto.TemplateDto(kind = slot, blocks = list, cols = cols)
    }
}
