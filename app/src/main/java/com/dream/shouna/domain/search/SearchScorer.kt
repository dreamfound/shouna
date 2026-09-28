package com.dream.shouna.domain.search

import com.dream.shouna.util.TextNormalizer

/**
 * 打分与高亮（ARCHITECTURE §4 F1-04 ①）。纯函数，无 Android 依赖。
 *
 * 被调用方：`SearchIndex.search`
 */
object SearchScorer {
    /** 命中打分：名称权重 > 分类名权重；未命中返回 0。 */
    fun score(doc: SearchDoc, normalizedQuery: String, config: SearchConfig): Int =
        when (matchType(doc, normalizedQuery)) {
            MatchType.NAME -> config.nameWeight
            MatchType.CATEGORY -> config.categoryWeight
            null -> 0
        }

    /**
     * 名称上的命中区间，用于结果行高亮。
     *
     * 归一化会改变串长（空白折叠 / 去首尾空白），故按「归一化结果 → 原串」的位置映射换算回
     * 原串下标后再返回，避免把高亮画到错误字符上。
     */
    fun highlightRange(name: String, normalizedQuery: String): IntRange? {
        if (normalizedQuery.isEmpty()) return null
        val (normalized, indexMap) = TextNormalizer.normalizeWithSourceIndex(name)
        val start = normalized.indexOf(normalizedQuery)
        if (start < 0) return null
        val end = start + normalizedQuery.length - 1
        // indexMap 恒与 normalized 等长；越界防御性裁剪。
        val sourceStart = indexMap.getOrElse(start) { return null }
        val sourceEnd = indexMap.getOrElse(end) { return null }
        return if (sourceEnd >= sourceStart) sourceStart..sourceEnd else null
    }

    /** 命中类型判定。 */
    fun matchType(doc: SearchDoc, normalizedQuery: String): MatchType? {
        if (normalizedQuery.isEmpty()) return null
        // 名称优先：名称命中即不再看分类名。
        if (TextNormalizer.containsNormalized(doc.normalizedName, normalizedQuery)) return MatchType.NAME
        val normalizedCategoryName = doc.normalizedCategoryName
        if (normalizedCategoryName != null &&
            TextNormalizer.containsNormalized(normalizedCategoryName, normalizedQuery)
        ) {
            return MatchType.CATEGORY
        }
        return null
    }
}
