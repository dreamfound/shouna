package com.dream.shouna.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `app_config` 表（docs/code-map/02-数据与领域.md）：键值配置。
 * 本页只写一行 `threshold_months = 6`（FR-27 超期阈值真源；P1 的 FR-29 复用同一行）。
 */
@Entity(tableName = "app_config")
data class AppConfigEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,
    @ColumnInfo(name = "value")
    val value: String,
)
