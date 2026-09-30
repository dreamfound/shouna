package com.dream.shouna.data.repository

import com.dream.shouna.data.local.dao.ConfigDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `app_config` 表实现（ARCHITECTURE-P0 §3.1 + P1-06）。
 * 种子写入 `threshold_months = 6`；解析失败（脏数据）时回落到默认值，不让一个坏值阻断详情页。
 *
 * 被调用方：`SearchViewModel` / `ItemDetailViewModel` / `LocationBrowseViewModel` /
 * `StatsViewModel`（读阈值）、`SettingsViewModel`（写阈值）
 */
@Singleton
class ConfigRepositoryImpl @Inject constructor(
    private val configDao: ConfigDao,
) : ConfigRepository {

    override suspend fun thresholdMonths(): Int =
        configDao.get(ConfigDao.KEY_THRESHOLD_MONTHS)?.toIntOrNull()
            ?: ConfigDao.DEFAULT_THRESHOLD_MONTHS

    override suspend fun setThresholdMonths(months: Int): Boolean =
        TODO("P1-06 ③: 写入 threshold_months（设置页仅给 3 / 6 / 12 三档）")
}
