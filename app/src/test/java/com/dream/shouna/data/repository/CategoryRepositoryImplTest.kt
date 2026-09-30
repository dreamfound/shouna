package com.dream.shouna.data.repository

import com.dream.shouna.data.local.FakeCategoryDao
import com.dream.shouna.data.local.entity.CategoryEntity
import com.dream.shouna.util.UuidIdGenerator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * P1-05 单测（纯 JVM，FR-44）：分类的增删改与「内置可改名不可删」。
 *
 * 无事务、无跨表：分类是**单表**写，删分类后物品回落「未分类」由 FK `ON DELETE SET NULL`
 * 保证 —— 那条走 androidTest（真库约束），本类只覆盖仓库层的判定与排序。
 */
class CategoryRepositoryImplTest {

    private val categoryDao = FakeCategoryDao(
        initial = listOf(
            CategoryEntity(id = "builtin-category-appliance", name = "电器", sortOrder = 1, isBuiltIn = true),
            CategoryEntity(id = "builtin-category-clothing", name = "衣物", sortOrder = 2, isBuiltIn = true),
        ),
    )
    private val repository = CategoryRepositoryImpl(categoryDao, UuidIdGenerator())

    @Test
    fun create_appendsAfterBuiltInsAndIsDeletable() = runTest {
        val created = repository.create(" 露营  ")

        // 名称去首尾空白后落库。
        assertThat(created.name).isEqualTo("露营")
        // 自定义（`is_built_in = 0`）→ 可删。
        assertThat(created.isBuiltIn).isFalse()
        // 排在同级末尾：chips 顺序 = `sort_order`，新分类不该插到内置分类前面。
        assertThat(created.sortOrder).isEqualTo(3)
        assertThat(repository.observeCategories().first().map { it.name })
            .containsExactly("电器", "衣物", "露营").inOrder()
    }

    @Test
    fun create_rejectsBlankName() = runTest {
        runCatching { repository.create("   ") }

        assertThat(categoryDao.snapshot()).hasSize(2)
        assertThat(repository.observeCategories().first().map { it.name })
            .containsExactly("电器", "衣物").inOrder()
    }

    @Test
    fun rename_alsoAppliesToBuiltInAndRejectsBlank() = runTest {
        assertThat(repository.rename("builtin-category-appliance", "小家电")).isTrue()
        assertThat(repository.observeCategories().first().first { it.isBuiltIn }.name).isEqualTo("小家电")

        assertThat(repository.rename("builtin-category-appliance", "  ")).isFalse()
        assertThat(repository.rename("no-such-id", "x")).isFalse()
    }

    @Test
    fun delete_removesCustomButRefusesBuiltIn() = runTest {
        val custom = repository.create("露营")

        assertThat(repository.delete(custom.id)).isTrue()
        assertThat(categoryDao.snapshot().map { it.id }).doesNotContain(custom.id)

        // 内置分类删不掉（判据在 SQL 的 `is_built_in = 0`，仓库层只转述受影响行数）。
        assertThat(repository.delete("builtin-category-appliance")).isFalse()
        assertThat(categoryDao.snapshot()).hasSize(2)
    }
}
