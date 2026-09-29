package com.dream.shouna.domain.search

import com.dream.shouna.util.TextNormalizer

/**
 * 内存搜索索引（ARCHITECTURE §4 F1-04 ①②③）：权重排序、失效过滤、高亮区间。
 * 纯 Kotlin，可在 JVM 上直接做微基准（1000 件 ≤300ms、5000 件 ≤800ms）。
 *
 * P0-04 起检索维度由 2 档扩到 4 档（名称 / 拼音 / 位置 / 分类），判定与权重在 [SearchScorer]；
 * 本类只负责「遍历 + 排序 + 截断」，不关心各档怎么判。
 *
 * 被调用方：SearchViewModel（懒构建，冷启动不阻塞）
 */
class SearchIndex private constructor(
    private val docs: List<SearchDoc>,
    private val config: SearchConfig,
) {
    /**
     * 文档列表指纹（size + 最大 lastModifiedAt）；变化即触发重建。
     * 调用关系：[fingerprintOf]（同一算法，无需构建索引即可比较）。
     */
    val fingerprint: Long = fingerprintOf(docs)

    /** 检索：输入即搜，返回按权重排序、截断到 limit 的命中列表。 */
    fun search(query: String, limit: Int): List<SearchHit> {
        val normalizedQuery = TextNormalizer.normalize(query)
        // 空查询（含纯空白）不产出任何结果，由 UI 决定展示什么。
        if (normalizedQuery.isEmpty()) return emptyList()

        return docs.asSequence()
            .mapNotNull { doc ->
                // 失效条目跳过：索引侧兜底，仓库层的 filterActive 已在数据入口做同一件事（§3.5）。
                if (!doc.status.isActive) return@mapNotNull null
                val matchType = SearchScorer.matchType(doc, normalizedQuery) ?: return@mapNotNull null
                ScoredHit(
                    hit = SearchHit(
                        doc = doc,
                        matchType = matchType,
                        // 只有名称命中才有可高亮区间（SearchHit.highlightRange 允许 null）：
                        // 拼音 / 位置 / 分类命中在名称上没有对应的连续片段。
                        highlightRange = if (matchType == MatchType.NAME) {
                            SearchScorer.highlightRange(doc.name, normalizedQuery)
                        } else {
                            null
                        },
                    ),
                    // 档位已判定 → 直接取权重，不重复判定（§4 P0-04 ④）。
                    score = SearchScorer.weightOf(matchType, config),
                )
            }
            // 同分时按 lastModifiedAt 倒序，保证结果顺序稳定可复现。
            .sortedWith(
                compareByDescending<ScoredHit> { it.score }
                    .thenByDescending { it.hit.doc.lastModifiedAt },
            )
            .take(limit.coerceAtLeast(0))
            .map { it.hit }
            .toList()
    }

    private data class ScoredHit(val hit: SearchHit, val score: Int)

    companion object {
        /** 由文档列表构建索引，输入需已过滤失效条目。 */
        fun build(docs: List<SearchDoc>, config: SearchConfig = SearchConfig()): SearchIndex =
            SearchIndex(docs = docs, config = config)

        /** 指纹计算（无需构建索引即可比较）。 */
        fun fingerprintOf(docs: List<SearchDoc>): Long {
            val size = docs.size.toLong()
            val maxLastModifiedAt = docs.maxOfOrNull { it.lastModifiedAt } ?: 0L
            return size * FINGERPRINT_SIZE_FACTOR + maxLastModifiedAt
        }

        /** 指纹中条目数所占权重，避免「件数变化但最大时间戳相同」被误判为同一份文档。 */
        private const val FINGERPRINT_SIZE_FACTOR = 1_000_003L
    }
}
