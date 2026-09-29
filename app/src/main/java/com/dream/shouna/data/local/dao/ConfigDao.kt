package com.dream.shouna.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dream.shouna.data.local.entity.AppConfigEntity

/**
 * `app_config` 表读写（ARCHITECTURE-P0 §3.1）：FR-27 超期阈值的真源。
 * 键名常量集中在 [ConfigKeys]，避免字面量散落。
 */
@Dao
interface ConfigDao {

    @Query("SELECT value FROM app_config WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: AppConfigEntity)

    companion object {
        /** 超期阈值（月）。P1 的 FR-29 的 3 / 6 / 12 复用同一行。 */
        const val KEY_THRESHOLD_MONTHS: String = "threshold_months"

        /** 默认 6 个月（ARCHITECTURE-P0 §3.3 种子）。 */
        const val DEFAULT_THRESHOLD_MONTHS: Int = 6
    }
}
