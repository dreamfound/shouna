package com.dream.shouna.util

import com.dream.shouna.domain.model.Location
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * FR-02 单测（纯 JVM）：面包屑派生。
 *
 * 覆盖正常路径、根级、以及两类降级 —— 父级缺失（截断）与**成环**（不死循环）。
 * 后两者是防御性用例：数据正常时不会走到，但不能让它们把应用挂住。
 */
class LocationPathTest {

    private val home = Location(id = "home", name = "家", isBuiltIn = false, parentId = null, sortOrder = 1)
    private val storage = Location(id = "storage", name = "储物间", isBuiltIn = false, parentId = "home", sortOrder = 2)
    private val box = Location(id = "box", name = "纸箱-07", isBuiltIn = false, parentId = "storage", sortOrder = 1)
    private val byId = listOf(home, storage, box).associateBy { it.id }

    @Test
    fun textOf_buildsFullPathFromRoot() {
        assertThat(LocationPath.textOf("box", byId)).isEqualTo("家 › 储物间 › 纸箱-07")
    }

    @Test
    fun textOf_rootNodeIsItsOwnPath() {
        assertThat(LocationPath.textOf("home", byId)).isEqualTo("家")
    }

    @Test
    fun textOf_missingParent_truncatesInsteadOfThrowing() {
        // storage 的父级 "home" 不在快照里 → 只拼得出一段。
        val orphan = Location(id = "box", name = "纸箱-07", isBuiltIn = false, parentId = "storage", sortOrder = 1)
        val partial = mapOf("box" to orphan)

        assertThat(LocationPath.textOf("box", partial)).isEqualTo("纸箱-07")
    }

    @Test
    fun textOf_unknownId_returnsEmpty() {
        assertThat(LocationPath.textOf("no-such-id", byId)).isEmpty()
    }

    @Test
    fun textOf_cycleStopsInsteadOfLoopingForever() {
        // a → b → a：父链成环（写入口已有防线，这里保证读路径不会挂死）。
        val a = Location(id = "a", name = "A", isBuiltIn = false, parentId = "b")
        val b = Location(id = "b", name = "B", isBuiltIn = false, parentId = "a")
        val cyclic = listOf(a, b).associateBy { it.id }

        val text = LocationPath.textOf("a", cyclic)

        assertThat(text.isNotEmpty()).isTrue()
        assertThat(text).contains("A")
    }

    @Test
    fun textsOf_computesEveryLocationInOnePass() {
        val texts = LocationPath.textsOf(listOf(home, storage, box))

        assertThat(texts).hasSize(3)
        assertThat(texts["storage"]).isEqualTo("家 › 储物间")
        assertThat(texts["box"]).isEqualTo("家 › 储物间 › 纸箱-07")
    }

    @Test
    fun separator_isUsedConsistentlyWithBreadcrumbCopy() {
        // 长按复制的是同一段文本，故分隔符必须是常量而非各处手写。
        assertThat(LocationPath.format(listOf(home, storage)))
            .isEqualTo("家" + LocationPath.SEPARATOR + "储物间")
    }

    // ---- P1-01：ID 序列路径（物化列 `location.path` 的唯一口径） ----------------------

    @Test
    fun buildIdPath_rootLevelKeepsLeadingSlash() {
        // 根级（父路径 = 空串）也必须产出 `/自身id/` —— 这与 `MIGRATION_1_2` 的
        // `'/' || id || '/'` 是同一形态。少了前导 `/` 就会让「新装库」与「升级库」
        // 的 `path` 长得不一样（P1 §3.2 明文要求两条路径同形）。
        assertThat(LocationPath.buildIdPath(selfId = "home", parentPath = "")).isEqualTo("/home/")
    }

    @Test
    fun buildIdPath_appendsUnderParentPath() {
        assertThat(LocationPath.buildIdPath(selfId = "storage", parentPath = "/home/"))
            .isEqualTo("/home/storage/")
        // 父路径末尾漏了分隔符时自动补上，不产出拼不上的路径。
        assertThat(LocationPath.buildIdPath(selfId = "storage", parentPath = "/home"))
            .isEqualTo("/home/storage/")
    }

    @Test
    fun isDescendantPath_includesSelfAndRejectsLookalikePrefix() {
        val ancestor = "/a/"
        assertThat(LocationPath.isDescendantPath(candidate = "/a/", ancestorPath = ancestor)).isTrue()
        assertThat(LocationPath.isDescendantPath(candidate = "/a/b/", ancestorPath = ancestor)).isTrue()
        // 整段 ID 匹配：`/ab/` 不是 `/a/` 的子孙（裸 id 拼接就会误判）。
        assertThat(LocationPath.isDescendantPath(candidate = "/ab/", ancestorPath = ancestor)).isFalse()
        // 空前缀不构成祖先关系（否则「空前缀匹配一切」会放行非法移动）。
        assertThat(LocationPath.isDescendantPath(candidate = "/a/", ancestorPath = "")).isFalse()
    }
}
