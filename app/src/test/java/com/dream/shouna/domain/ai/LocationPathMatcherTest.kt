package com.dream.shouna.domain.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * AI-01 单测（纯 JVM，不触网）：位置路径 → 命中链 / 待新建层。
 *
 * 覆盖 AI-01 验收的 5 条（逐层全命中 / 部分命中 / 全未命中 / 同名节点精确相等 / 空树），
 * 另加两条口径边界：拼音同音命中、**不得**用包含匹配或首字母缩写。
 */
class LocationPathMatcherTest {

    private val matcher = LocationPathMatcher()

    private val tree = listOf(
        AiLocationNode(id = "root", name = "家", parentId = null),
        AiLocationNode(id = "living", name = "客厅", parentId = "root"),
        AiLocationNode(id = "drawer", name = "抽屉", parentId = "living"),
        AiLocationNode(id = "cabinet", name = "客厅储物柜", parentId = "root"),
        AiLocationNode(id = "garage", name = "车库", parentId = "root"),
    )

    @Test
    fun match_allLayersHit_returnsWholeChain() {
        val result = matcher.match(listOf("家", "客厅", "抽屉"), tree)

        assertThat(result.matchedIds).containsExactly("root", "living", "drawer").inOrder()
        assertThat(result.matchedId).isEqualTo("drawer")
        assertThat(result.hasPending).isFalse()
    }

    @Test
    fun match_lastLayerMissing_isMarkedPending() {
        val result = matcher.match(listOf("家", "客厅", "鞋柜"), tree)

        assertThat(result.matchedIds).containsExactly("root", "living").inOrder()
        assertThat(result.matchedId).isEqualTo("living")
        assertThat(result.pendingNames).containsExactly("鞋柜")
    }

    @Test
    fun match_firstLayerMissing_makesEveryLaterLayerPending() {
        // 父节点还不存在 ⇒ 其后各层即使能在树里找到同名节点，也不算命中。
        val result = matcher.match(listOf("车库", "抽屉"), tree)

        assertThat(result.nothingMatched).isTrue()
        assertThat(result.pendingNames).containsExactly("车库", "抽屉").inOrder()
    }

    @Test
    fun match_wholePathUnknown_reportsNothingMatched() {
        val result = matcher.match(listOf("阁楼"), tree)

        assertThat(result.matchedIds).isEmpty()
        assertThat(result.matchedId).isNull()
        assertThat(result.pendingNames).containsExactly("阁楼")
    }

    @Test
    fun match_pinyinLayer_hitsExistingNode() {
        // 「keting」与「客厅」同音：归一化不等、拼音全拼相等 ⇒ 命中（路径仍须从根层开始）。
        val result = matcher.match(listOf("家", "keting", "抽屉"), tree)

        assertThat(result.matchedIds).containsExactly("root", "living", "drawer").inOrder()
    }

    @Test
    fun match_pathNotStartingFromRoot_doesNotMatchDeeperNodes() {
        // 只给「客厅/抽屉」而不给根层：根层就对不上（候选是「家」），其后各层一并待新建。
        val result = matcher.match(listOf("客厅", "抽屉"), tree)

        assertThat(result.nothingMatched).isTrue()
        assertThat(result.pendingNames).containsExactly("客厅", "抽屉").inOrder()
    }

    @Test
    fun match_doesNotUseSubstringOrInitialLetters() {
        // 「客厅储物」不得命中「客厅储物柜」（包含匹配会误判）；「kt」也不得命中「客厅」（首字母缩写会误判）。
        val partial = matcher.match(listOf("家", "客厅储物"), tree)
        assertThat(partial.matchedIds).containsExactly("root")
        assertThat(partial.pendingNames).containsExactly("客厅储物")

        val initials = matcher.match(listOf("kt"), tree)
        assertThat(initials.nothingMatched).isTrue()
        assertThat(initials.pendingNames).containsExactly("kt")
    }

    @Test
    fun match_emptyTree_reportsEveryLayerPending() {
        val result = matcher.match(listOf("家", "客厅"), emptyList())

        assertThat(result.matchedIds).isEmpty()
        assertThat(result.pendingNames).containsExactly("家", "客厅").inOrder()
    }

    @Test
    fun match_emptyOrNullPath_returnsEmptyResult() {
        assertThat(matcher.match(null, tree).pendingNames).isEmpty()
        assertThat(matcher.match(emptyList(), tree).nothingMatched).isTrue()
        assertThat(matcher.match(listOf("  ", ""), tree).nothingMatched).isTrue()
    }
}
