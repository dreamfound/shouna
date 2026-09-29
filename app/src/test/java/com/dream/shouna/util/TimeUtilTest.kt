package com.dream.shouna.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * P0-03 单测（纯 JVM）：相对时间分档与 FR-27 超期判定。
 *
 * 全部用例都**传入固定的 `now`**，不依赖 `Instant.now()` —— 否则边界用例会在月底 / 年末偶发失败。
 */
class TimeUtilTest {

    private val timeUtil = TimeUtil()
    private val now = 1_700_000_000_000L

    private fun daysAgo(days: Long): Long = now - days * MILLIS_PER_DAY
    private fun minutesAgo(minutes: Long): Long = now - minutes * 60_000L
    private fun hoursAgo(hours: Long): Long = now - hours * 3_600_000L

    // ---- 相对时间分档 --------------------------------------------------------------

    @Test
    fun relativeText_firstMinuteIsJustNow() {
        assertThat(timeUtil.relativeText(minutesAgo(0), now)).isEqualTo(TimeUtil.JUST_NOW)
    }

    @Test
    fun relativeText_minutesHoursDaysMonthsYears() {
        assertThat(timeUtil.relativeText(minutesAgo(5), now)).isEqualTo("5 分钟前")
        assertThat(timeUtil.relativeText(hoursAgo(3), now)).isEqualTo("3 小时前")
        assertThat(timeUtil.relativeText(daysAgo(20), now)).isEqualTo("20 天前")
        assertThat(timeUtil.relativeText(daysAgo(90), now)).isEqualTo("3 个月前")
        assertThat(timeUtil.relativeText(daysAgo(400), now)).isEqualTo("1 年前")
    }

    @Test
    fun relativeText_clockSkewFallsBackToJustNow() {
        // 时钟回拨：now 早于目标时间 → 不产出负数文案。
        assertThat(timeUtil.relativeText(now + 60_000L, now)).isEqualTo(TimeUtil.JUST_NOW)
    }

    @Test
    fun relativeConfirmText_neverConfirmedUsesDedicatedCopy() {
        assertThat(timeUtil.relativeConfirmText(null, now)).isEqualTo(TimeUtil.NEVER_CONFIRMED)
        assertThat(timeUtil.relativeConfirmText(daysAgo(30), now)).isEqualTo("1 个月前")
    }

    // ---- FR-27 超期判定 ------------------------------------------------------------

    @Test
    fun isOverdue_neverConfirmedCountsAsOverdue() {
        assertThat(timeUtil.isOverdue(lastConfirmedAt = null, thresholdMonths = 6, now = now)).isTrue()
    }

    @Test
    fun isOverdue_withinThresholdIsNotOverdue() {
        // 100 天 < 6 × 30 = 180 天。
        assertThat(timeUtil.isOverdue(daysAgo(100), thresholdMonths = 6, now = now)).isFalse()
    }

    @Test
    fun isOverdue_exactlyAtThresholdIsNotOverdue() {
        // 判定是「严格早于」阈值 → 恰好 180 天不算超期。
        assertThat(timeUtil.isOverdue(daysAgo(180), thresholdMonths = 6, now = now)).isFalse()
        assertThat(timeUtil.isOverdue(daysAgo(181), thresholdMonths = 6, now = now)).isTrue()
    }

    @Test
    fun isOverdue_respectsThresholdArgument() {
        // P1 的 FR-29 会复用同一判定，阈值可配（3 / 6 / 12）。
        assertThat(timeUtil.isOverdue(daysAgo(100), thresholdMonths = 3, now = now)).isTrue()
        assertThat(timeUtil.isOverdue(daysAgo(100), thresholdMonths = 12, now = now)).isFalse()
    }

    @Test
    fun isOverdue_thresholdMonthsMatchesRelativeTextBuckets() {
        // 同一套「30 天 / 月」换算：显示「6 个月前」时必然已超期，不会自相矛盾。
        val sixMonthsAgo = daysAgo(6 * 30L)

        assertThat(timeUtil.relativeText(sixMonthsAgo, now)).isEqualTo("6 个月前")
        assertThat(timeUtil.isOverdue(sixMonthsAgo, thresholdMonths = 6, now = now)).isFalse()
        assertThat(timeUtil.isOverdue(sixMonthsAgo - 1, thresholdMonths = 6, now = now)).isTrue()
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
