package com.macaupos.nativeapp.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `pos_bootstrap_config` 本地快取 —— 一店一 row。
 *
 * web 版餐牌真源:categories/menu_items/tables/rules/printer_groups 全部係
 * 該 row 入面嘅 JSON 欄位(見 `/api/pos/bootstrap` GET)。
 * native 直接打 Supabase 讀同表(anon SELECT,RLS 放行 —— docs/92),
 * 快取落呢度,**斷網都開得了單**。
 */
@Entity(tableName = "bootstrap_cache")
data class BootstrapCacheEntity(
    @PrimaryKey val storeId: String,
    val storeName: String,
    val currency: String?,
    /** 整個 bootstrap payload 原樣 JSON(storeId/storeName/currency/categories/menuItems/tables/rules/printerGroups) */
    val payload: String,
    val sourceVersion: Long,
    val fetchedAt: Long,
)
