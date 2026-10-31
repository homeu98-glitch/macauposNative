package com.macaupos.nativeapp.domain

/**
 * 每日單號 —— 移植 web `daily-order-seq.ts` `maxDailySeqFromOrders`:
 *
 * 掃當日訂單嘅 `localOrderNo`(格式 = prefix + 純數字),取 max → 下一個係 max+1。
 * 斷網都連續:料源係**本地 Room**(離線建嘅單已經喺入面)。
 *
 * 前綴:堂食 = "訂單"(web `pos-app.tsx` `sequencePrefix`;quick mode 另有前綴,P1-B 接)。
 * 今日 key = 當地時區 yyyy-MM-dd(web `dateKeyOf` 同款)。
 */
object DailyOrderSeq {

    /** 單號顯示格式:`訂單12`(prefix + 當日序號,無補零 —— web 同款)。 */
    fun format(prefix: String, seq: Int): String = "$prefix$seq"

    /**
     * 由既有單號清單計出**下一個**當日序號。
     * @param ordersToday (localOrderNo, createdAtEpochMs) —— 只傳今日嘅單(或全量,內部過濾)
     * @param todayKey 當地 yyyy-MM-dd
     */
    fun nextSeq(
        orders: List<Pair<String?, Long>>,
        prefix: String,
        todayKey: String,
    ): Int = maxSeq(orders, prefix, todayKey) + 1

    /** web `maxDailySeqFromOrders` 直譯:掃 prefix+digits,時間唔係今日嘅跳過(解析唔到就保守計入)。 */
    fun maxSeq(
        orders: List<Pair<String?, Long>>,
        prefix: String,
        todayKey: String,
    ): Int {
        val escaped = Regex.escape(prefix)
        val re = Regex("^$escaped(\\d+)$")
        var max = 0
        for ((no, ts) in orders) {
            if (no.isNullOrBlank()) continue
            val m = re.matchEntire(no.trim()) ?: continue
            // epoch <= 0 = 解析唔到 → 保守計入(避免因一個壞時間戳而撞號)
            if (ts > 0 && dateKeyOf(ts) != todayKey) continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (n > max) max = n
        }
        return max
    }

    /** 當地時區 yyyy-MM-dd(web `dateKeyOf` 同款語意)。 */
    fun dateKeyOf(epochMs: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMs
        return String.format(
            java.util.Locale.US,
            "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    fun todayKey(nowMs: Long = System.currentTimeMillis()): String = dateKeyOf(nowMs)
}
