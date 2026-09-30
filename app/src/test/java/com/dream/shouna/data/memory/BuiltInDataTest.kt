package com.dream.shouna.data.memory

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * F1-02 单测 ②：哨兵唯一性 + 内置分类 8 条且 id 不重复（ARCHITECTURE §3.4，纯 JVM）。
 *
 * 被测调用：
 *   BuiltInData.BUILT_IN_LOCATIONS / UNSPECIFIED_LOCATION        —— 哨兵唯一性
 *   BuiltInData.UNSPECIFIED_LOCATION_ID                          —— 非空串（§3.4 不变量 1）
 *   BuiltInData.BUILT_IN_CATEGORIES / BUILT_IN_CATEGORY_COUNT     —— 8 条 + id 唯一
 * 无下游注入，直接读常量（object，无需构造）。
 */
class BuiltInDataTest {

    @Test
    fun unspecifiedLocation_isTheOnlyBuiltInLocation() {
        assertThat(BuiltInData.BUILT_IN_LOCATIONS).hasSize(1)
        assertThat(BuiltInData.BUILT_IN_LOCATIONS.first()).isEqualTo(BuiltInData.UNSPECIFIED_LOCATION)
        assertThat(BuiltInData.UNSPECIFIED_LOCATION.isBuiltIn).isTrue()
        assertThat(BuiltInData.UNSPECIFIED_LOCATION.parentId).isNull()
    }

    @Test
    fun unspecifiedLocationId_isNeverBlank() {
        assertThat(BuiltInData.UNSPECIFIED_LOCATION_ID.isNotBlank()).isTrue()
        assertThat(BuiltInData.UNSPECIFIED_LOCATION.id).isEqualTo(BuiltInData.UNSPECIFIED_LOCATION_ID)
    }

    @Test
    fun builtInCategories_hasExactlyEightEntries() {
        // 断言 size == BuiltInData.BUILT_IN_CATEGORY_COUNT
        assertThat(BuiltInData.BUILT_IN_CATEGORIES).hasSize(BuiltInData.BUILT_IN_CATEGORY_COUNT)
        assertThat(BuiltInData.BUILT_IN_CATEGORIES).hasSize(8)
        // 名称与 id 都不允许为空串。
        assertThat(BuiltInData.BUILT_IN_CATEGORIES).containsNoDuplicates()
        BuiltInData.BUILT_IN_CATEGORIES.forEach {
            assertThat(it.id.isNotBlank()).isTrue()
            assertThat(it.name.isNotBlank()).isTrue()
        }
    }

    @Test
    fun builtInCategories_haveUniqueIds() {
        val ids = BuiltInData.BUILT_IN_CATEGORIES.map { it.id }
        assertThat(ids.toSet()).hasSize(ids.size)
        assertThat(ids).containsNoDuplicates()
        assertThat(ids).doesNotContain(BuiltInData.UNSPECIFIED_LOCATION_ID)
    }

    // ---- P1-01：种子位置的 ID 序列路径 ----------------------------------------------

    @Test
    fun seedLocationPaths_coverEverySeededLocationInCanonicalShape() {
        val seeded = BuiltInData.BUILT_IN_LOCATIONS + BuiltInData.DEFAULT_LOCATION_TREE

        // 每个种子位置都有路径，且形态与 `MIGRATION_1_2` 的 `'/' || id || '/'` 一致：
        // 以 `/` 开头、以 `/` 结尾、末段为自身 id。这条断言就是「新装库 ≡ 升级库」的守门人
        // （P1 §3.2：两条路径必须产出同一形态）。
        assertThat(BuiltInData.SEED_LOCATION_PATHS.keys).containsExactlyElementsIn(seeded.map { it.id })
        seeded.forEach { location ->
            val path = BuiltInData.SEED_LOCATION_PATHS.getValue(location.id)
            assertThat(path.startsWith("/")).isTrue()
            assertThat(path.endsWith("/")).isTrue()
            assertThat(path).endsWith("/${location.id}/")
        }

        // 子节点的路径 = 父节点路径 + 自身 id + `/`。
        assertThat(BuiltInData.SEED_LOCATION_PATHS.getValue(BuiltInData.DEFAULT_ROOT_LOCATION_ID))
            .isEqualTo("/${BuiltInData.DEFAULT_ROOT_LOCATION_ID}/")
        BuiltInData.DEFAULT_LOCATION_TREE
            .filter { it.parentId != null }
            .forEach { child ->
                val parentPath = BuiltInData.SEED_LOCATION_PATHS.getValue(child.parentId!!)
                assertThat(BuiltInData.SEED_LOCATION_PATHS.getValue(child.id))
                    .isEqualTo("$parentPath${child.id}/")
            }
    }
}
