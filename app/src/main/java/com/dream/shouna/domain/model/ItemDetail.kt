package com.dream.shouna.domain.model

/**
 * 详情页展示模型。
 *
 * F1 只渲染「名称 + 一行相对确认时间 +『✓ 还在』」；P0-02 起补 [locationPath]（FR-02 面包屑），
 * P0-03 起补「最后变动」与两个状态动作。
 */
data class ItemDetail(
    val item: StoredItem,
    /** 未分类 = null。 */
    val categoryName: String?,
    /**
     * FR-02：位置面包屑文本（如「家 › 储物间 › 纸箱-07」），由
     * [com.dream.shouna.util.LocationPath] 从父链派生。
     * 位置缺失（脏数据 / 并发删除）时为空串，由 UI 决定不显示该行。
     */
    val locationPath: String = "",
)
