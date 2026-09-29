package com.dream.shouna.data.repository

import com.dream.shouna.data.local.dao.ConfigDao

/**
 * 应用配置读取（ARCHITECTURE-P0 §3.1）：本页只有一个消费者 —— FR-27 的超期阈值。
 *
 * 之所以做成仓库而不是直接注入 DAO：UI 层不该知道配置存在哪张表；而**改值**（FR-47 设置页）
 * 属 P1，届时只需给本接口加一个写方法，不动调用方。
 */
interface ConfigRepository {
    /** 超期阈值（月）。表里没有值时回落 [ConfigDao.DEFAULT_THRESHOLD_MONTHS]。 */
    suspend fun thresholdMonths(): Int
}
