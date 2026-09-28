package com.dream.shouna.data.repository

import com.dream.shouna.data.memory.BuiltInData
import com.dream.shouna.data.memory.InMemoryStore
import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.util.TextNormalizer
import com.dream.shouna.util.TimeUtil
import com.dream.shouna.util.UuidIdGenerator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * F1-03 单测（ARCHITECTURE §4 F1-03 ⑤，纯 JVM）：
 * locationId 恒哨兵、status 恒 `in_storage`、时间戳矩阵、连续录入 30 次不丢条目。
 *
 * 构造依赖：InMemoryStore（真实，内存源）+ CategoryRepositoryImpl（真实，只读常量）
 *           + UuidIdGenerator / TimeUtil（真实实现，均为无状态纯工具）。
 * 断言读取：createItemQuick / confirmItem 的返回值，以及 InMemoryStore.snapshot()。
 */
class ItemRepositoryImplTest {

    private val store = InMemoryStore()
    private val timeUtil = TimeUtil()
    private val repository = ItemRepositoryImpl(
        store = store,
        categoryRepository = CategoryRepositoryImpl(),
        idGenerator = UuidIdGenerator(),
        timeUtil = timeUtil,
    )

    @Test
    fun createItemQuick_locationId_isAlwaysSentinelAndNeverBlank() = runTest {
        val uncategorized = repository.createItemQuick(name = "电风扇", categoryId = null, note = null)
        val categorized = repository.createItemQuick(name = "电风扇", categoryId = "builtin-category-appliance", note = "卧室那台")

        listOf(uncategorized, categorized).forEach { item ->
            assertThat(item.locationId).isEqualTo(BuiltInData.UNSPECIFIED_LOCATION_ID)
            assertThat(item.locationId.isNotBlank()).isTrue()
            assertThat(item.id.isNotBlank()).isTrue()
            // 派生字段：归一化名与检索键同源（§3.2）。
            assertThat(item.normalizedName).isEqualTo(TextNormalizer.normalize(item.name))
            // 置空字段（§3.2 / §7.1）。
            assertThat(item.aliasBlob).isEmpty()
            assertThat(item.quantity).isEqualTo(1)
        }
        // 同名物品按 id 区分（§0）。
        assertThat(uncategorized.id).isNotEqualTo(categorized.id)
        assertThat(uncategorized.name).isEqualTo(categorized.name)
    }

    @Test
    fun createItemQuick_status_isAlwaysInStorage() = runTest {
        val uncategorized = repository.createItemQuick(name = "电风扇", categoryId = null, note = null)
        val categorized = repository.createItemQuick(name = "说明书", categoryId = "builtin-category-stationery", note = null)

        listOf(uncategorized, categorized).forEach { item ->
            assertThat(item.status).isEqualTo(ItemStatus.IN_STORAGE)
            assertThat(item.status.code).isEqualTo("in_storage")
            assertThat(item.status.isActive).isTrue()
        }
        // F1 只产生 IN_STORAGE（§3.5）：仓库层过滤后条目数不变。
        assertThat(filterActive(store.snapshot())).hasSize(2)
    }

    @Test
    fun createItemQuick_writesCreatedAtAndLastModifiedAt_only() = runTest {
        // 新建物品：createdAt = lastModifiedAt = now；lastConfirmedAt 保持 null
        val before = timeUtil.nowMillis()
        val item = repository.createItemQuick(name = "电风扇", categoryId = null, note = "卧室")
        val after = timeUtil.nowMillis()

        assertThat(item.createdAt).isEqualTo(item.lastModifiedAt)
        assertThat(item.createdAt).isAtLeast(before)
        assertThat(item.createdAt).isAtMost(after)
        assertThat(item.lastConfirmedAt).isNull()
    }

    @Test
    fun confirmItem_touchesLastConfirmedAtOnly() = runTest {
        // 点「还在」：lastModifiedAt 不动，lastConfirmedAt = now
        val created = repository.createItemQuick(name = "电风扇", categoryId = null, note = null)
        assertThat(created.lastConfirmedAt).isNull()

        val confirmed = requireNotNull(repository.confirmItem(created.id))

        assertThat(confirmed.lastConfirmedAt).isNotNull()
        // 时间戳矩阵（§3.5）：只动 lastConfirmedAt。
        assertThat(confirmed.lastModifiedAt).isEqualTo(created.lastModifiedAt)
        assertThat(confirmed.createdAt).isEqualTo(created.createdAt)
        assertThat(confirmed.name).isEqualTo(created.name)
        assertThat(confirmed.normalizedName).isEqualTo(created.normalizedName)
        // 写回的是同一条记录（源内唯一）。
        assertThat(store.snapshot()).hasSize(1)
        assertThat(store.findById(created.id)?.lastConfirmedAt).isEqualTo(confirmed.lastConfirmedAt)
    }

    @Test
    fun createItemQuick_thirtyConsecutiveTimes_keepsEveryItem() = runTest {
        repeat(30) { index ->
            repository.createItemQuick(
                name = "物品 $index",
                categoryId = if (index % 2 == 0) "builtin-category-other" else null,
                note = null,
            )
        }

        val all = store.snapshot()
        assertThat(all).hasSize(30)
        assertThat(all.map { it.id }.toSet()).hasSize(30)
        assertThat(all.map { it.name }.toSet()).hasSize(30)
        assertThat(all.count { it.categoryId != null }).isEqualTo(15)
    }
}
