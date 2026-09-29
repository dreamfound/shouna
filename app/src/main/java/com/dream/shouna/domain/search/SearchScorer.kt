package com.dream.shouna.domain.search

import com.dream.shouna.util.TextNormalizer

/**
 * 打分与高亮（ARCHITECTURE-P0 §4 P0-04 ④）。纯函数，无 Android 依赖。
 *
 * 命中优先级 = 方法内的判定顺序 = **名称 → 拼音 → 位置 → 分类**：
 * 命中即返回，不继续往下看。这样一条记录只会归入权重最高的那一档。
 *
 * 被调用方：`SearchIndex.search`
 */
object SearchScorer {

    /** 命中打分；未命中返回 0。 */
    fun score(doc: SearchDoc, normalizedQuery: String, config: SearchConfig): Int =
        weightOf(matchType(doc, normalizedQuery), config)

    /**
     * 档位 → 权重。
     *
     * 单独暴露是为了让索引在**已经判定过档位**之后直接取权重，不重复跑一遍判定
     * （`SearchIndex.search` 的路径：判档 → 用 [weightOf] 取分，每条记录只判定一次）。
     */
    fun weightOf(matchType: MatchType?, config: SearchConfig): Int = when (matchType) {
        MatchType.NAME -> config.nameWeight
        MatchType.PINYIN -> config.pinyinWeight
        MatchType.LOCATION -> config.locationWeight
        MatchType.CATEGORY -> config.categoryWeight
        null -> 0
    }

    /**
     * 名称上的命中区间，用于结果行高亮。
     *
     * 归一化会改变串长（空白折叠 / 去首尾空白），故按「归一化结果 → 原串」的位置映射换算回
     * 原串下标后再返回，避免把高亮画到错误字符上。
     * **仅名称命中适用**：拼音 / 位置 / 分类命中在名称上没有对应片段，返回 null。
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

    /**
     * 命中类型判定（顺序 = 优先级）。
     *
     * 拼音档的判定要注意：**首字母串很短**（「电风扇」→ `dfs`），若用户输入的查询串也是短串
     * （如 `d`），`contains` 会大面积命中。这是有意的取舍 —— 拼音前缀命中是 FR-20 期望的行为
     * （「打首字母就能找到」），精确性由权重与结果排序承担，而不是在这里做长度门槛。
     */
    fun matchType(doc: SearchDoc, normalizedQuery: String): MatchType? {
        if (normalizedQuery.isEmpty()) return null

        // ① 名称子串
        if (TextNormalizer.containsNormalized(doc.normalizedName, normalizedQuery)) return MatchType.NAME

        // ② FR-20 拼音：全拼或首字母
        if (doc.pinyinFull.isNotEmpty() &&
            TextNormalizer.containsNormalized(doc.pinyinFull, normalizedQuery)
        ) {
            return MatchType.PINYIN
        }
        if (doc.pinyinInitial.isNotEmpty() &&
            TextNormalizer.containsNormalized(doc.pinyinInitial, normalizedQuery)
        ) {
            return MatchType.PINYIN
        }

        // ③ FR-02 位置路径（中文原文与拼音两条）
        if (doc.normalizedLocationPath.isNotEmpty() &&
            TextNormalizer.containsNormalized(doc.normalizedLocationPath, normalizedQuery)
        ) {
            return MatchType.LOCATION
        }
        if (doc.pinyinLocationPath.isNotEmpty() &&
            TextNormalizer.containsNormalized(doc.pinyinLocationPath, normalizedQuery)
        ) {
            return MatchType.LOCATION
        }

        // ④ 分类名
        val normalizedCategoryName = doc.normalizedCategoryName
        if (normalizedCategoryName != null &&
            TextNormalizer.containsNormalized(normalizedCategoryName, normalizedQuery)
        ) {
            return MatchType.CATEGORY
        }

        return null
    }
}
