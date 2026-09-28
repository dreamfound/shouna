package com.dream.shouna.util

import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 时间工具（ARCHITECTURE §0）：core library desugaring + `java.time`；模型内一律 Long epoch millis。
 * 时间戳矩阵（§3.5）：新建物品写 [nowMillis]；点「还在」只写 `lastConfirmedAt`；改名 / 改分类 / 改备注不动时间。
 *
 * 被调用方：`ItemRepositoryImpl.createItemQuick` → createdAt / lastModifiedAt
 *           `ItemRepositoryImpl.confirmItem` → lastConfirmedAt
 *           `ItemDetailViewModel` → [relativeConfirmText]
 */
@Singleton
class TimeUtil @Inject constructor() {
    /** epoch millis UTC。 */
    fun nowMillis(): Long = Instant.now().toEpochMilli()

    /**
     * 详情页那一行相对时间：「3 个月前」；从未确认返回「从未确认」（FR-26 的可见反馈）。
     *
     * 分档（常量兜底，ARCHITECTURE §7.3 未钉死取值）：
     * <1 分钟「刚刚确认」／<1 小时「N 分钟前」／<24 小时「N 小时前」／
     * <30 天「N 天前」／<12 个月「N 个月前」／其余「N 年前」。
     */
    fun relativeConfirmText(lastConfirmedAt: Long?, now: Long = nowMillis()): String {
        if (lastConfirmedAt == null) return NEVER_CONFIRMED

        val elapsed = Duration.ofMillis(now - lastConfirmedAt)
        // 时钟回拨（now 早于确认时间）时兜底为「刚刚确认」，不产出负数文案。
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

    companion object {
        /** 从未确认的文案（FR-26）。 */
        const val NEVER_CONFIRMED: String = "从未确认"

        private const val JUST_NOW = "刚刚确认"
        private const val MINUTES_PER_HOUR = 60L
        private const val HOURS_PER_DAY = 24L
        private const val DAYS_PER_MONTH = 30L
        private const val DAYS_PER_YEAR = 365L
    }
}
