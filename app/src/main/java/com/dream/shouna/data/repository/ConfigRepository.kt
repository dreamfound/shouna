package com.dream.shouna.data.repository

import com.dream.shouna.data.local.dao.ConfigDao

/**
 * 应用配置读写（ARCHITECTURE-P0 §3.1 + P1 §4 P1-06）：消费者是 FR-27 / FR-29 的超期阈值。
 *
 * 之所以做成仓库而不是直接注入 DAO：UI 层不该知道配置存在哪张表。
 * **P1-06 起加写方法**（设置页），调用方不变。
 */
interface ConfigRepository {

    /** 超期阈值（月）。表里没有值时回落 [ConfigDao.DEFAULT_THRESHOLD_MONTHS]。 */
    suspend fun thresholdMonths(): Int

    /**
     * FR-47：写入超期阈值。设置页只给 **3 / 6 / 12** 三个档位（P1 §0）。
     * 返回是否写入成功（非法档位由调用方拦下，此处只做落库）。
     */
    suspend fun setThresholdMonths(months: Int): Boolean
}
