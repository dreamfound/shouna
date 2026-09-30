package com.dream.shouna.data.repository

import com.dream.shouna.data.local.dao.ConfigDao
import com.dream.shouna.data.local.entity.AppConfigEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    override fun observeThresholdMonths(): Flow<Int> =
        configDao.observe(ConfigDao.KEY_THRESHOLD_MONTHS)
            // 与 thresholdMonths() 逐字同一回落（缺失 / 非数字 → 默认 6），两条读路径不各写一份。
            .map { raw -> raw?.toIntOrNull() ?: ConfigDao.DEFAULT_THRESHOLD_MONTHS }

    override suspend fun setThresholdMonths(months: Int): Boolean {
        // 只接受设置页给出的三档（P1 §0「设置页可改 3 / 6 / 12」）——非法档位在仓库层拦下，
        // 免得一个手滑（或将来某处直接调仓库）把阈值写成 0 → 全部物品恒判超期。
        if (months !in ALLOWED_THRESHOLD_MONTHS) return false
        configDao.put(
            AppConfigEntity(
                key = ConfigDao.KEY_THRESHOLD_MONTHS,
                value = months.toString(),
            ),
        )
        return true
    }

    companion object {
        /** 允许写入的阈值档位（FR-29 / FR-47）。 */
        val ALLOWED_THRESHOLD_MONTHS: List<Int> = listOf(3, 6, 12)
    }
}
