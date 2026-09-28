package com.dream.shouna.domain.search

/**
 * 单条命中：供结果行渲染「名称 + 关键词高亮」。
 */
data class SearchHit(
    val doc: SearchDoc,
    val matchType: MatchType,
    /** 在 `doc.name` 上的高亮区间；分类命中时可为 null。 */
    val highlightRange: IntRange?,
)
