package com.dream.shouna.domain.search

import com.dream.shouna.domain.model.ItemStatus

/**
 * 索引文档（ARCHITECTURE §4 F1-04 ①）：由 `List<StoredItem>` 派生，检索维度 = 归一化名 + 分类名（无拼音）。
 */
data class SearchDoc(
    val itemId: String,
    val name: String,
    val normalizedName: String,
    val categoryId: String?,
    val categoryName: String?,
    val normalizedCategoryName: String?,
    val status: ItemStatus,
    /** 指纹计算用（size + 最大 lastModifiedAt）。 */
    val lastModifiedAt: Long,
)
