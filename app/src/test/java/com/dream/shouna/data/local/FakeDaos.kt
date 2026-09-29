package com.dream.shouna.data.local

import com.dream.shouna.data.local.dao.CategoryDao
import com.dream.shouna.data.local.dao.ItemDao
import com.dream.shouna.data.local.dao.LocationDao
import com.dream.shouna.data.local.dao.LocationItemCount
import com.dream.shouna.data.local.dao.RecentSearchDao
import com.dream.shouna.data.local.entity.CategoryEntity
import com.dream.shouna.data.local.entity.ItemEntity
import com.dream.shouna.data.local.entity.LocationEntity
import com.dream.shouna.data.local.entity.RecentSearchEntity
import com.dream.shouna.domain.model.ItemStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 测试用内存 DAO 替身（ARCHITECTURE-P0 §8.1-13）：纯 JVM，不依赖 Android / SQLite / Room 运行时。
 *
 * 分工：仓库层的**业务逻辑**用这里的替身在 JVM 单测里覆盖；「SQL 与 schema 是否正确」
 * 交给 androidTest（`room-testing`）。这样既不用引入 Robolectric，也不放弃 Room 的验证。
 *
 * 取向：行为与 Room 生成的实现保持一致 —— 排序、**受影响行数**、未命中不抛异常。
 */
class FakeItemDao : ItemDao {

    private val rows = MutableStateFlow<List<ItemEntity>>(emptyList())

    /** 直接读快照，便于断言「写了什么」。 */
    fun snapshot(): List<ItemEntity> = rows.value

    override fun observeAll(): Flow<List<ItemEntity>> =
        rows.map { list -> list.sortedByDescending { it.createdAt } }

    override fun observeByLocation(locationId: String): Flow<List<ItemEntity>> =
        rows.map { list -> list.filter { it.locationId == locationId }.sortedByDescending { it.createdAt } }

    override fun observeActiveCounts(): Flow<List<LocationItemCount>> =
        rows.map { list ->
            list.filter { it.status != ItemStatus.GONE }
                .groupingBy { it.locationId }
                .eachCount()
                .map { (locationId, count) -> LocationItemCount(locationId = locationId, itemCount = count) }
        }

    override suspend fun findById(id: String): ItemEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun insert(item: ItemEntity) {
        rows.value = rows.value + item
    }

    override suspend fun updateLastConfirmedAt(id: String, confirmedAt: Long): Int =
        updateWhere(predicate = { it.id == id }, transform = { it.copy(lastConfirmedAt = confirmedAt) })

    override suspend fun updateStatus(id: String, status: ItemStatus, modifiedAt: Long): Int =
        updateWhere(
            predicate = { it.id == id },
            transform = { it.copy(status = status, lastModifiedAt = modifiedAt) },
        )

    override suspend fun moveItems(sourceId: String, targetId: String, modifiedAt: Long): Int =
        updateWhere(
            predicate = { it.locationId == sourceId },
            transform = { it.copy(locationId = targetId, lastModifiedAt = modifiedAt) },
        )

    override suspend fun moveItemsAndSetStatus(
        sourceId: String,
        targetId: String,
        status: ItemStatus,
        modifiedAt: Long,
    ): Int = updateWhere(
        predicate = { it.locationId == sourceId },
        transform = { it.copy(locationId = targetId, status = status, lastModifiedAt = modifiedAt) },
    )

    override suspend fun countIn(locationId: String): Int = rows.value.count { it.locationId == locationId }

    override suspend fun count(): Int = rows.value.size

    private fun updateWhere(
        predicate: (ItemEntity) -> Boolean,
        transform: (ItemEntity) -> ItemEntity = { it },
    ): Int {
        var affected = 0
        rows.value = rows.value.map { row ->
            if (predicate(row)) {
                affected += 1
                transform(row)
            } else {
                row
            }
        }
        return affected
    }
}

/** `category` 表替身。默认空表，由测试显式灌入（等价于建库种子的结果）。 */
class FakeCategoryDao(initial: List<CategoryEntity> = emptyList()) : CategoryDao {

    private val rows = MutableStateFlow(initial)

    fun snapshot(): List<CategoryEntity> = rows.value

    override fun observeAll(): Flow<List<CategoryEntity>> =
        rows.map { list -> list.sortedBy { it.sortOrder } }

    override suspend fun insertAll(items: List<CategoryEntity>) {
        rows.value = rows.value + items
    }

    override suspend fun count(): Int = rows.value.size
}

/** `location` 表替身（P0-02）。行为对齐 Room：`parent_id IS NULL` 的根级查询、受影响行数。 */
class FakeLocationDao(initial: List<LocationEntity> = emptyList()) : LocationDao {

    private val rows = MutableStateFlow(initial)

    fun snapshot(): List<LocationEntity> = rows.value

    override fun observeAll(): Flow<List<LocationEntity>> =
        rows.map { list -> list.sortedWith(compareBy({ it.sortOrder }, { it.name })) }

    override fun observeChildren(parentId: String?): Flow<List<LocationEntity>> =
        rows.map { list -> list.filter { it.parentId == parentId }.sortedWith(compareBy({ it.sortOrder }, { it.name })) }

    override suspend fun findById(id: String): LocationEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun childrenOf(parentId: String): List<LocationEntity> =
        rows.value.filter { it.parentId == parentId }

    override suspend fun siblings(parentId: String?): List<LocationEntity> =
        rows.value.filter { it.parentId == parentId }

    override suspend fun allUsable(): List<LocationEntity> = rows.value.filterNot { it.isBuiltIn }

    override suspend fun recentUsed(excludeId: String, limit: Int): List<LocationEntity> =
        rows.value
            .filter { !it.isBuiltIn && it.id != excludeId && it.lastUsedAt != null }
            .sortedByDescending { it.lastUsedAt }
            .take(limit)

    override suspend fun insert(location: LocationEntity) {
        rows.value = rows.value + location
    }

    override suspend fun rename(id: String, name: String): Int =
        updateWhere(predicate = { it.id == id }, transform = { it.copy(name = name) })

    override suspend fun setNote(id: String, note: String?): Int =
        updateWhere(predicate = { it.id == id }, transform = { it.copy(note = note) })

    override suspend fun touchLastUsed(id: String, at: Long): Int =
        updateWhere(predicate = { it.id == id }, transform = { it.copy(lastUsedAt = at) })

    override suspend fun delete(id: String): Int {
        val before = rows.value.size
        rows.value = rows.value.filterNot { it.id == id }
        return before - rows.value.size
    }

    private fun updateWhere(
        predicate: (LocationEntity) -> Boolean,
        transform: (LocationEntity) -> LocationEntity = { it },
    ): Int {
        var affected = 0
        rows.value = rows.value.map { row ->
            if (predicate(row)) {
                affected += 1
                transform(row)
            } else {
                row
            }
        }
        return affected
    }
}

/**
 * `recent_search` 表替身（P0-04）。行为对齐 Room 的 `INSERT OR REPLACE`：
 * 主键冲突**与唯一索引冲突**都替换 —— 归一化同词只留最近一条。
 */
class FakeRecentSearchDao(initial: List<RecentSearchEntity> = emptyList()) : RecentSearchDao {

    private val rows = MutableStateFlow(initial)

    fun snapshot(): List<RecentSearchEntity> = rows.value

    override fun observeRecent(limit: Int): Flow<List<RecentSearchEntity>> =
        rows.map { list -> list.sortedByDescending { it.searchedAt }.take(limit) }

    override suspend fun upsert(item: RecentSearchEntity) {
        rows.value = rows.value.filterNot {
            it.query == item.query || it.normalizedQuery == item.normalizedQuery
        } + item
    }

    override suspend fun trim(keep: Int) {
        rows.value = rows.value.sortedByDescending { it.searchedAt }.take(keep)
    }

    override suspend fun count(): Int = rows.value.size
}
