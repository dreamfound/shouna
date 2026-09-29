package com.dream.shouna.data.local

import androidx.room.TypeConverter
import com.dream.shouna.domain.model.ItemStatus

/**
 * Room 类型转换（ARCHITECTURE-P0 §3.2）：`ItemStatus` ↔ 稳定字符串 code。
 *
 * 用 **code 而非 `enum.name`** 落库（F1 已定的稳定 code 口径，§3.2）；
 * 遇到未知 code 兜底为 [ItemStatus.IN_STORAGE]，与「默认只查 active」的过滤取向一致。
 */
class Converters {
    @TypeConverter
    fun fromItemStatus(status: ItemStatus): String = status.code

    @TypeConverter
    fun toItemStatus(code: String): ItemStatus =
        ItemStatus.fromCode(code) ?: ItemStatus.IN_STORAGE
}
