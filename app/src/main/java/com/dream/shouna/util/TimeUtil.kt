package com.dream.shouna.util

import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 时间工具（ARCHITECTURE §0 / ARCHITECTURE-P0 §3.5）：core library desugaring + `java.time`；
 * 模型内一律 Long epoch millis。
 *
 * 时间戳矩阵（§3.5）：新建物品写 [nowMillis]；点「还在」只写 `lastConfirmedAt`；
 * 标记「不在了」/ 恢复 / 移动**只写 `lastModifiedAt`**；改名 / 改分类 / 改备注都不动时间。
 *
 * P0-03 新增： [relativeText]（「最后变动」复用同一分档）、[isOverdue]（FR-27 超期判定）。
 *
 * 被调用方：`ItemRepositoryImpl`（写时间戳）、`LocationRepositoryImpl`（写 `last_used_at`）、
 *             `ItemDetailViewModel` / `SearchViewModel`（组装文案）
 */
@Singleton
class TimeUtil @Inject constructor() {
    /** epoch millis UTC。 */
    fun nowMillis(): Long = Instant.now().toEpochMilli()

    /**
     * 「最后确认」那一行：从未确认 → [NEVER_CONFIRMED]；已确认 → [relativeText]。
     * 签名与 F1 保持一致（调用点无需改动）。
     */
    fun relativeConfirmText(lastConfirmedAt: Long?, now: Long = nowMillis()): String =
        lastConfirmedAt?.let { relativeText(it, now) } ?: NEVER_CONFIRMED

    /**
     * 纯相对短语（**不带前缀**），供「最后确认」与「最后变动」共用。
     *
     * 分档（常量兜底，ARCHITECTURE §7.3 未钉死取值）：
     * <1 分钟「刚刚」／<1 小时「N 分钟前」／<24 小时「N 小时前」／
     * <30 天「N 天前」／<12 个月「N 个月前」／其余「N 年前」。
     */
    fun relativeText(at: Long, now: Long = nowMillis()): String {
        val elapsed = Duration.ofMillis(now - at)
        // 时钟回拨（now 早于该时间点）时兜底为「刚刚」，不产出负数文案。
        if (elapsed.isNegative) return JUST_NOW

        val minutes = elapsed.toMinutes()
        return when {
            minutes < 1 -> JUST_NOW
            minutes < MINUTES_PER_HOUR -> "$minutes 分钟前"
            elapsed.toHours() < HOURS_PER_DAY -> "${elapsed.toHours()} 小时前"
            elapsed.toDays() < DAYS_PER_MONTH -> "${elapsed.toDays()} 天前"
            elapsed.toDays() < DAYS_PER_YEAR -> "${elapsed.toDays() / DAYS_PER_MONTH} 个月前"
            else -> "${elapsed.toDays() / DAYS_PER_YEAR} 年前"
        }
    }

    /**
     * FR-27 超期判定：**从未确认**，或最后确认早于「now − `thresholdMonths` 个月」。
     *
     * 月份按 [DAYS_PER_MONTH] 天折算 —— 与 [relativeText] 的分档用**同一套换算**，
     * 否则会出现「显示 5 个月前」却被判定为超期 6 个月的自相矛盾。
     */
    fun isOverdue(lastConfirmedAt: Long?, thresholdMonths: Int, now: Long = nowMillis()): Boolean {
        if (lastConfirmedAt == null) return true
        val threshold = now - thresholdMonths * DAYS_PER_MONTH * MILLIS_PER_DAY
        return lastConfirmedAt < threshold
    }

    companion object {
        /** 从未确认的文案（FR-26）。 */
        const val NEVER_CONFIRMED: String = "从未确认"

        /** 「刚刚」的文案；同时用于「最后确认」与「最后变动」（P0-03 统一，避免「刚刚确认」被拼进变动行）。 */
        const val JUST_NOW: String = "刚刚"

        private const val MINUTES_PER_HOUR = 60L
        private const val HOURS_PER_DAY = 24L
        private const val DAYS_PER_MONTH = 30L
        private const val DAYS_PER_YEAR = 365L
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
