package com.dream.shouna.data.repository

import com.dream.shouna.data.local.dao.ConfigDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `app_config` 表的只读实现（ARCHITECTURE-P0 §3.1）。
 * 种子写入 `threshold_months = 6`；解析失败（脏数据）时回落到默认值，不让一个坏值阻断详情页。
 */
@Singleton
class ConfigRepositoryImpl @Inject constructor(
    private val configDao: ConfigDao,
) : ConfigRepository {

    override suspend fun thresholdMonths(): Int =
        configDao.get(ConfigDao.KEY_THRESHOLD_MONTHS)?.toIntOrNull()
            ?: ConfigDao.DEFAULT_THRESHOLD_MONTHS
}
