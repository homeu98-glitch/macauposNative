package com.macaupos.nativeapp.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 同步出站佇列 —— offline-first 嘅心臟(移植 web `queue-outbox.ts` 語意)。
 *
 * 流程:UI 寫操作先入呢張表(status=pending)+ 即刻返 UI 成功;
 * Sync Engine(P1)背景 `flush` 推上 Supabase → 成功變 done → `reconcile` 對帳。
 * 斷網時 pending 堆喺本地,恢復網絡自動補送。
 *
 * `dedupeKey` 對齊 web `print-dedupe.ts` 語意:同一個業務動作(例:補送同一張單)
 * 重複入隊時用唯一鍵擋住,防重複建立訂單/重複打印。
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["dedupeKey"], unique = true),
        Index(value = ["status", "createdAt"]),
    ],
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 業務種類:"order" | "shift" | "soldout" | "print_job" | ... */
    val kind: String,
    /** 冪等鍵,例:"order:<localId>" / "print:<jobId>:<seq>"(unique) */
    val dedupeKey: String,
    /** JSON payload(對應 Supabase 表欄位;P1 按 kind 定 schema) */
    val payload: String,
    /** "pending" | "inflight" | "done" | "failed" */
    val status: String = "pending",
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val sentAt: Long? = null,
)
