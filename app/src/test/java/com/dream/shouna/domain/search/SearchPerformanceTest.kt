package com.dream.shouna.domain.search

import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.util.TextNormalizer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * P0-04 验收项里的 JVM 微基准（ARCHITECTURE-P0 §4 P0-04 / §9）：
 * **1000 件 ≤ 300ms、5000 件 ≤ 800ms**。
 *
 * 计时口径取**保守的一侧**：索引构建 + 一次检索一起计（真实 UX 上的最坏情形正是
 * 「文档变了 → 重建索引 → 立刻出结果」），而不是只计 `search` 那一段。
 * 预算按文档给的数字；实测通常有一到两个数量级的余量，故不是脆弱断言。
 *
 * 打印出来的毫秒数会进 Gradle 测试报告（`system-out`），可作验收证据。
 */
class SearchPerformanceTest {

    @Test
    fun thousandDocsStayUnderBudget() {
        assertWithinBudget(count = 1_000, budgetMillis = 300L)
    }

    @Test
    fun fiveThousandDocsStayUnderBudget() {
        assertWithinBudget(count = 5_000, budgetMillis = 800L)
    }

    private fun assertWithinBudget(count: Int, budgetMillis: Long) {
        // 预热：先跑一份小索引，避免把 JIT 首次编译算进正式计时。
        SearchIndex.build((1..50).map { index -> doc(index) }).search(QUERY, 50)

        val docs = (1..count).map { index -> doc(index) }
        val startedAt = System.nanoTime()
        val index = SearchIndex.build(docs)
        val hits = index.search(QUERY, 50)
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI

        println(
            "[SearchPerformanceTest] 建索引 + 检索 $count 件：${elapsedMillis}ms" +
                "（预算 ${budgetMillis}ms），命中 ${hits.size} 条",
        )

        assertThat(hits).isNotEmpty()
        assertThat(elapsedMillis).isLessThan(budgetMillis)
    }

    private fun doc(index: Int): SearchDoc {
        val name = "电风扇-$index"
        return SearchDoc(
            itemId = "id-$index",
            name = name,
            normalizedName = TextNormalizer.normalize(name),
            pinyinFull = "dianfengshan-$index",
            pinyinInitial = "dfs$index",
            categoryId = "builtin-category-appliance",
            categoryName = "电器",
            normalizedCategoryName = "电器",
            locationPath = "家 › 储物间 › 纸箱-$index",
            normalizedLocationPath = "家 › 储物间 › 纸箱-$index",
            pinyinLocationPath = "jia › chuwujian › zhixiang-$index",
            status = ItemStatus.IN_STORAGE,
            lastModifiedAt = index.toLong(),
        )
    }

    private companion object {
        /** 命中全部条目的查询（走名称子串档 + 拼音档）。 */
        const val QUERY = "dfs"

        const val NANOS_PER_MILLI = 1_000_000L
    }
}
