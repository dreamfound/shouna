package com.dream.shouna.domain.search

import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.util.TextNormalizer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * F1-04 单测（纯 JVM）：名称子串命中、权重排序、失效过滤、高亮区间、指纹变化。
 * 微基准口径：1000 件 ≤300ms、5000 件 ≤800ms。
 *
 * 被测调用：
 *   SearchIndex.build(docs) → SearchIndex.search(query, limit)
 *     → 内部调用 TextNormalizer.normalize() / SearchScorer.matchType() / score() / highlightRange()
 *   SearchIndex.fingerprintOf(docs)（与 build 产出的 fingerprint 比对）
 * 输入构造：本类自备 [doc] 夹具；失效过滤在仓库层（filterActive）已完成，
 *           此处验证索引侧对 `status` 的兜底行为（不返回非 active 文档）。
 */
class SearchIndexTest {

    @Test
    fun search_matchesNameSubstring() {
        // 「风扇」命中「电风扇」
        val index = SearchIndex.build(
            listOf(
                doc(itemId = "1", name = "电风扇"),
                doc(itemId = "2", name = "电吹风"),
            ),
        )

        val hits = index.search(query = "风扇", limit = 10)

        assertThat(hits.map { it.doc.itemId }).containsExactly("1")
        assertThat(hits.first().matchType).isEqualTo(MatchType.NAME)
        val range = requireNotNull(hits.first().highlightRange)
        assertThat("电风扇".substring(range.first, range.last + 1)).isEqualTo("风扇")
    }

    @Test
    fun search_matchesCategoryName() {
        val index = SearchIndex.build(
            listOf(
                doc(itemId = "1", name = "插线板", categoryName = "电器"),
                doc(itemId = "2", name = "螺丝刀", categoryName = "工具"),
            ),
        )

        val hits = index.search(query = "电器", limit = 10)

        assertThat(hits.map { it.doc.itemId }).containsExactly("1")
        // 分类命中时名称上没有可高亮区间 → null。
        assertThat(hits.first().matchType).isEqualTo(MatchType.CATEGORY)
        assertThat(hits.first().highlightRange).isNull()
    }

    @Test
    fun search_ranksNameMatchAboveCategoryMatch() {
        val index = SearchIndex.build(
            listOf(
                // 仅分类名命中。
                doc(itemId = "category-only", name = "插线板", categoryName = "电器"),
                // 名称命中，应排在前面。
                doc(itemId = "name-hit", name = "电器收纳箱", categoryName = "工具"),
            ),
        )

        val hits = index.search(query = "电器", limit = 10)

        assertThat(hits.map { it.doc.itemId }).containsExactly("name-hit", "category-only").inOrder()
        assertThat(hits.first().matchType).isEqualTo(MatchType.NAME)
    }

    @Test
    fun search_excludesInactiveDocs() {
        val index = SearchIndex.build(
            listOf(
                doc(itemId = "active", name = "电风扇"),
                doc(itemId = "gone", name = "电风扇", status = ItemStatus.GONE),
            ),
        )

        val hits = index.search(query = "风扇", limit = 10)

        assertThat(hits.map { it.doc.itemId }).containsExactly("active")
        assertThat(hits.none { it.doc.status == ItemStatus.GONE }).isTrue()
    }

    @Test
    fun search_truncatesToLimit() {
        val docs = (1..5).map { doc(itemId = "id-$it", name = "风扇$it") }
        val index = SearchIndex.build(docs)

        assertThat(index.search(query = "风扇", limit = 2)).hasSize(2)
        assertThat(index.search(query = "风扇", limit = 99)).hasSize(5)
        // 空查询不产出结果，由 UI 决定展示什么。
        assertThat(index.search(query = "   ", limit = 10)).isEmpty()
    }

    @Test
    fun fingerprint_changesWithDocumentFingerprint() {
        val base = (1..3).map { doc(itemId = "id-$it", name = "物品$it", lastModifiedAt = 100L) }
        val moreDocs = base + doc(itemId = "id-4", name = "物品4", lastModifiedAt = 100L)
        val newerDoc = listOf(base.first().copy(lastModifiedAt = 200L)) + base.drop(1)

        assertThat(SearchIndex.fingerprintOf(base)).isEqualTo(SearchIndex.build(base).fingerprint)
        // 件数变化 → 指纹变化。
        assertThat(SearchIndex.fingerprintOf(moreDocs)).isNotEqualTo(SearchIndex.fingerprintOf(base))
        // 最大 lastModifiedAt 变化 → 指纹变化。
        assertThat(SearchIndex.fingerprintOf(newerDoc)).isNotEqualTo(SearchIndex.fingerprintOf(base))
        // 同一份文档 → 指纹稳定。
        assertThat(SearchIndex.fingerprintOf(base)).isEqualTo(SearchIndex.fingerprintOf(base.toList()))
    }

    private fun doc(
        itemId: String,
        name: String,
        categoryName: String? = null,
        status: ItemStatus = ItemStatus.IN_STORAGE,
        lastModifiedAt: Long = 0L,
    ): SearchDoc = SearchDoc(
        itemId = itemId,
        name = name,
        normalizedName = TextNormalizer.normalize(name),
        categoryId = categoryName?.let { "builtin-category-$it" },
        categoryName = categoryName,
        normalizedCategoryName = categoryName?.let { TextNormalizer.normalize(it) },
        status = status,
        lastModifiedAt = lastModifiedAt,
    )
}
