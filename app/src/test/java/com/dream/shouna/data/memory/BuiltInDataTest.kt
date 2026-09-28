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
}
