package com.dream.shouna.data.repository

import com.dream.shouna.data.local.FakeConfigDao
import com.dream.shouna.data.local.dao.ConfigDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * P1-06 ③ 单测（纯 JVM，FR-29 / FR-47）：超期阈值的读与写。
 *
 * 阈值是「设置页 → C-4 清单」的唯一纽带，因此两条边界都要钉住：
 * 缺值回落默认 6；越档拒绝（防一处手滑把阈值写成 0 → 全部物品恒判超期）。
 */
class ConfigRepositoryImplTest {

    @Test
    fun thresholdMonths_fallsBackToDefaultWhenMissingOrCorrupt() = runTest {
        assertThat(ConfigRepositoryImpl(FakeConfigDao()).thresholdMonths())
            .isEqualTo(ConfigDao.DEFAULT_THRESHOLD_MONTHS)

        // 脏数据（非数字）同样回落，不让一个坏值把详情页打穿。
        val corrupt = FakeConfigDao(mapOf(ConfigDao.KEY_THRESHOLD_MONTHS to "六个月"))
        assertThat(ConfigRepositoryImpl(corrupt).thresholdMonths())
            .isEqualTo(ConfigDao.DEFAULT_THRESHOLD_MONTHS)
    }

    @Test
    fun setThresholdMonths_writesChosenTierAndReadsBack() = runTest {
        val dao = FakeConfigDao()
        val repository = ConfigRepositoryImpl(dao)

        ConfigRepositoryImpl.ALLOWED_THRESHOLD_MONTHS.forEach { months ->
            assertThat(repository.setThresholdMonths(months)).isTrue()
            assertThat(repository.thresholdMonths()).isEqualTo(months)
        }
        assertThat(dao.snapshot()[ConfigDao.KEY_THRESHOLD_MONTHS]).isEqualTo("12")
    }

    @Test
    fun setThresholdMonths_rejectsOutOfRangeTiers() = runTest {
        val dao = FakeConfigDao()
        val repository = ConfigRepositoryImpl(dao)

        assertThat(repository.setThresholdMonths(0)).isFalse()
        assertThat(repository.setThresholdMonths(-1)).isFalse()
        assertThat(repository.setThresholdMonths(99)).isFalse()
        // 拒绝时不落库，读回仍是默认。
        assertThat(dao.snapshot()).isEmpty()
        assertThat(repository.thresholdMonths()).isEqualTo(ConfigDao.DEFAULT_THRESHOLD_MONTHS)
    }
}
