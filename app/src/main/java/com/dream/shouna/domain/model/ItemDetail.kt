package com.dream.shouna.domain.model

/**
 * 详情页展示模型（ARCHITECTURE §4 F1-02 模型清单）。
 * F1 详情页只渲染：名称 + 一行相对确认时间 +「✓ 还在」。
 */
data class ItemDetail(
    val item: StoredItem,
    /** 未分类 = null。 */
    val categoryName: String?,
)
