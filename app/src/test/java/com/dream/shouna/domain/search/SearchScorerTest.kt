package com.dream.shouna.domain.search

import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.util.TextNormalizer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * P0-04 单测（纯 JVM）：四档命中判定与权重次序（名称 > 拼音 > 位置 = 分类）、
 * 拼音（全拼 / 首字母）与位置路径（中文 / 路径拼音）的命中、高亮只属名称档。
 *
 * 与 F1 的 `SearchIndexTest` 的分工：F1 那份**不动**（只管名称 / 分类两档），
 * 本类只管 P0 新增的拼音 / 位置两档与四档权重 —— 两份合起来覆盖 `SearchIndex.search` 的全部档位。
 *
 * 输入构造：本类自备 [doc] 夹具（拼音列等价于 `item.pinyin_*` 的持久化值，
 * 位置路径等价于索引构建时实时派生的值）。
 */
class SearchScorerTest {

    private val config = SearchConfig()

    // ---- 拼音档（FR-20） ----------------------------------------------------------

    @Test
    fun pinyinFullAndInitialBothHit() {
        val fan = doc(name = "电风扇", pinyinFull = "dianfengshan", pinyinInitial = "dfs")

        assertThat(SearchScorer.matchType(fan, "dianfengshan")).isEqualTo(MatchType.PINYIN)
        assertThat(SearchScorer.matchType(fan, "dfs")).isEqualTo(MatchType.PINYIN)
        // 子串语义：拼音也支持前缀命中。
        assertThat(SearchScorer.matchType(fan, "dianfeng")).isEqualTo(MatchType.PINYIN)
    }

    /**
     * 首字母必须**按字序**：
     * `电(d) 风(f) 扇(s)` → `dfs`。
     *
     * 与 PRD `04-用户故事与验收要点` US-03 / `05-需求池` FR-20 的验收例一致（该两处已于 2026-09-29 更正为「dfs」）；
     * 乱序（`dsf`）不命中 —— 首字母档不做乱序或模糊匹配。
     */
    @Test
    fun initialLettersFollowCharacterOrder() {
        val fan = doc(name = "电风扇", pinyinFull = "dianfengshan", pinyinInitial = "dfs")

        assertThat(SearchScorer.matchType(fan, "dfs")).isEqualTo(MatchType.PINYIN)
        assertThat(SearchScorer.matchType(fan, "dsf")).isNull()
    }

    // ---- 位置档（FR-02） ----------------------------------------------------------

    @Test
    fun locationPathHitsInBothChineseAndPinyin() {
        val fan = doc(
            name = "电风扇",
            locationPath = "家 › 储物间",
            pinyinLocationPath = "jia › chuwujian",
        )

        assertThat(SearchScorer.matchType(fan, "储物间")).isEqualTo(MatchType.LOCATION)
        assertThat(SearchScorer.matchType(fan, "chuwujian")).isEqualTo(MatchType.LOCATION)
        assertThat(SearchScorer.matchType(fan, "jia")).isEqualTo(MatchType.LOCATION)
    }

    @Test
    fun nameHitBeatsPinyinAndLocationHit() {
        val byName = doc(itemId = "name", name = "储物间标签", locationPath = "家")
        val byLocation = doc(
            itemId = "location",
            name = "电风扇",
            locationPath = "家 › 储物间",
            pinyinLocationPath = "jia › chuwujian",
        )

        assertThat(SearchScorer.score(byName, "储物间", config))
            .isGreaterThan(SearchScorer.score(byLocation, "储物间", config))
    }

    @Test
    fun weightsFollowTheDocumentedLadder() {
        // 名称 > 拼音 > 位置 = 分类（PRD 08 §7.x / P0 §4 ④ 的「名称… > 拼音/别名 > 位置/分类」）。
        assertThat(config.nameWeight).isGreaterThan(config.pinyinWeight)
        assertThat(config.pinyinWeight).isGreaterThan(config.locationWeight)
        assertThat(config.locationWeight).isEqualTo(config.categoryWeight)
        // 未命中不得分。
        assertThat(SearchScorer.weightOf(null, config)).isEqualTo(0)
        assertThat(SearchScorer.score(doc(name = "台灯"), "冰箱", config)).isEqualTo(0)
    }

    @Test
    fun highlightRangeOnlyAppliesToNameMatch() {
        // 名称命中：可高亮（「电风扇」的「风扇」在下标 1..2）；拼音命中在名称上没有对应片段 → null。
        assertThat(SearchScorer.highlightRange(name = "电风扇", normalizedQuery = "风扇")).isEqualTo(1..2)
        assertThat(SearchScorer.highlightRange(name = "电风扇", normalizedQuery = "dfs")).isNull()
    }

    // ---- 端到端（SearchIndex 四档合跑） -------------------------------------------

    @Test
    fun indexSearchesByNamePinyinAndLocation() {
        val index = SearchIndex.build(
            listOf(
                doc(
                    itemId = "fan",
                    name = "电风扇",
                    pinyinFull = "dianfengshan",
                    pinyinInitial = "dfs",
                    locationPath = "家 › 储物间",
                    pinyinLocationPath = "jia › chuwujian",
                ),
                doc(itemId = "lamp", name = "台灯", pinyinFull = "taideng", pinyinInitial = "td"),
            ),
        )

        assertThat(index.search("风扇", 10).map { it.doc.itemId }).containsExactly("fan")
        assertThat(index.search("dianfengshan", 10).map { it.doc.itemId }).containsExactly("fan")
        assertThat(index.search("dfs", 10).map { it.doc.itemId }).containsExactly("fan")
        assertThat(index.search("储物间", 10).map { it.doc.itemId }).containsExactly("fan")
        assertThat(index.search("chuwujian", 10).map { it.doc.itemId }).containsExactly("fan")
        assertThat(index.search("台灯", 10).map { it.doc.itemId }).containsExactly("lamp")

        // 档位与高亮：位置命中不给名称区间。
        val byLocation = index.search("chuwujian", 10).single()
        assertThat(byLocation.matchType).isEqualTo(MatchType.LOCATION)
        assertThat(byLocation.highlightRange).isNull()
        // 名称命中仍排在位置命中之前（同名物品里名称优先）。
        val mixed = SearchIndex.build(
            listOf(
                doc(itemId = "location", name = "纸箱", locationPath = "家 › 风扇专区"),
                doc(itemId = "name", name = "风扇", locationPath = "家"),
            ),
        )
        assertThat(mixed.search("风扇", 10).map { it.doc.itemId })
            .containsExactly("name", "location").inOrder()
    }

    private fun doc(
        itemId: String = "id-1",
        name: String,
        pinyinFull: String = "",
        pinyinInitial: String = "",
        locationPath: String = "",
        pinyinLocationPath: String = "",
        categoryName: String? = null,
        status: ItemStatus = ItemStatus.IN_STORAGE,
        lastModifiedAt: Long = 0L,
        lastConfirmedAt: Long? = null,
    ): SearchDoc = SearchDoc(
        itemId = itemId,
        name = name,
        normalizedName = TextNormalizer.normalize(name),
        pinyinFull = pinyinFull,
        pinyinInitial = pinyinInitial,
        categoryId = categoryName?.let { "builtin-category-$it" },
        categoryName = categoryName,
        normalizedCategoryName = categoryName?.let { TextNormalizer.normalize(it) },
        locationPath = locationPath,
        normalizedLocationPath = TextNormalizer.normalize(locationPath),
        pinyinLocationPath = pinyinLocationPath,
        status = status,
        lastModifiedAt = lastModifiedAt,
        lastConfirmedAt = lastConfirmedAt,
    )
}
