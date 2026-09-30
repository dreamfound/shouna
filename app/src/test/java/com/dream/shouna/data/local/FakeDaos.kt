package com.dream.shouna.data.local

import com.dream.shouna.data.local.dao.CategoryDao
import com.dream.shouna.data.local.dao.ConfigDao
import com.dream.shouna.data.local.dao.ItemDao
import com.dream.shouna.data.local.dao.LocationDao
import com.dream.shouna.data.local.dao.LocationItemCount
import com.dream.shouna.data.local.dao.RecentSearchDao
import com.dream.shouna.data.local.entity.AppConfigEntity
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

    // --- P1-02 / 03 / 04（FR-28、补丁式更新、统计清单）---------------------------------

    override suspend fun updateFields(
        id: String,
        applyCategory: Boolean,
        categoryId: String?,
        applyAliases: Boolean,
        aliasBlob: String,
        applyQuantity: Boolean,
        quantity: Int,
        applyNote: Boolean,
        note: String?,
        pinyinFull: String,
        pinyinInitial: String,
    ): Int = updateWhere(
        predicate = { it.id == id },
        transform = { row ->
            row.copy(
                categoryId = if (applyCategory) categoryId else row.categoryId,
                aliasBlob = if (applyAliases) aliasBlob else row.aliasBlob,
                quantity = if (applyQuantity) quantity else row.quantity,
                note = if (applyNote) note else row.note,
                pinyinFull = if (applyAliases) pinyinFull else row.pinyinFull,
                pinyinInitial = if (applyAliases) pinyinInitial else row.pinyinInitial,
            )
        },
    )

    override suspend fun confirmActiveInSubtree(prefix: String, confirmedAt: Long): Int =
        updateWhere(
            predicate = { row ->
                row.status != ItemStatus.GONE && row.locationId in idsInSubtree(prefix)
            },
            transform = { it.copy(lastConfirmedAt = confirmedAt) },
        )

    override fun observeOverdue(thresholdAt: Long): Flow<List<ItemEntity>> =
        rows.map { list ->
            list.filter { it.status != ItemStatus.GONE }
                .filter { it.lastConfirmedAt == null || it.lastConfirmedAt < thresholdAt }
                .sortedBy { it.lastConfirmedAt }
        }

    override fun observeToBePutBack(): Flow<List<ItemEntity>> =
        rows.map { list ->
            list.filter { row ->
                row.status == ItemStatus.TO_BE_PUT_BACK ||
                    (row.status != ItemStatus.GONE && row.locationId in temporaryLocationIds)
            }.sortedByDescending { it.lastModifiedAt }
        }

    override fun observeActiveCount(): Flow<Int> =
        rows.map { list -> list.count { it.status != ItemStatus.GONE } }

    override suspend fun putBack(
        id: String,
        locationId: String,
        status: ItemStatus,
        modifiedAt: Long,
    ): Int = updateWhere(
        predicate = { it.id == id },
        // 与真实 SQL 同形：位置 + 状态 + last_modified_at 一处写完（不含 last_confirmed_at）。
        transform = { it.copy(locationId = locationId, status = status, lastModifiedAt = modifiedAt) },
    )

    override suspend fun moveItem(id: String, locationId: String, modifiedAt: Long): Int =
        updateWhere(
            predicate = { it.id == id },
            transform = { it.copy(locationId = locationId, lastModifiedAt = modifiedAt) },
        )

    /**
     * `location.id → location.path`（ID 序列路径）。假的 `item` 表无法 join `location`，
     * 故由测试显式给出；[idsInSubtree] 用它复现真实 SQL 的区间语义。
     */
    var locationPaths: Map<String, String> = emptyMap()

    /** 用户标记过的临时位置 id（`location.is_temporary = 1`）。 */
    var temporaryLocationIds: Set<String> = emptySet()

    /**
     * 子树（**含自身**）的 id 集 —— 等价于 SQL 的
     * `path >= :prefix AND path < :prefix || char(0xFFFF)`。
     *
     * 用 `startsWith` 复现是**精确等价**而非近似：ID 序列路径带收尾 `/`，任何以 `prefix` 开头的
     * 路径都落在区间内，反之（如 `/ab/` 之于前缀 `/a/`）在首处不同字符上就已大于上界。
     */
    private fun idsInSubtree(prefix: String): Set<String> =
        locationPaths.filterValues { it.startsWith(prefix) }.keys

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

    // --- P1-05（FR-44）--------------------------------------------------------------

    override suspend fun insert(item: CategoryEntity) {
        rows.value = rows.value + item
    }

    override suspend fun maxSortOrder(): Int = rows.value.maxOfOrNull { it.sortOrder } ?: 0

    override suspend fun rename(id: String, name: String): Int =
        updateWhere(predicate = { it.id == id }, transform = { it.copy(name = name) })

    /** 内置分类被挡下（受影响行数 0）—— 与真实 SQL 的 `AND is_built_in = 0` 同语义。 */
    override suspend fun deleteCustom(id: String): Int {
        val target = rows.value.firstOrNull { it.id == id }
        if (target == null || target.isBuiltIn) return 0
        rows.value = rows.value.filterNot { it.id == id }
        return 1
    }

    private fun updateWhere(
        predicate: (CategoryEntity) -> Boolean,
        transform: (CategoryEntity) -> CategoryEntity,
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

    // --- P1-01 / P1-02（FR-04 / 06 / 21）---------------------------------------------

    override suspend fun updateParentAndPath(id: String, parentId: String?, path: String): Int =
        updateWhere(
            predicate = { it.id == id },
            transform = { it.copy(parentId = parentId, path = path) },
        )

    override suspend fun updatePath(id: String, path: String): Int =
        updateWhere(predicate = { it.id == id }, transform = { it.copy(path = path) })

    /** 区间语义（含自身）—— 见 `FakeItemDao.idsInSubtree` 的等价性说明。 */
    override suspend fun subtreeOf(prefix: String): List<LocationEntity> =
        rows.value.filter { it.path.startsWith(prefix) }

    override suspend fun descendantsOf(prefix: String, excludeId: String): List<LocationEntity> =
        rows.value.filter { it.id != excludeId && it.path.startsWith(prefix) }

    override suspend fun setTemporary(id: String, flag: Boolean): Int =
        updateWhere(predicate = { it.id == id }, transform = { it.copy(isTemporary = flag) })

    override fun observeUsableCount(): Flow<Int> =
        rows.map { list -> list.count { !it.isBuiltIn } }

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
 * `app_config` 表替身（P1-05 / P1-06）。行为对齐 Room 的 `INSERT OR REPLACE`：
 * 同 key 覆盖。默认空表 —— 由「读回落默认值」的用例覆盖。
 *
 * P1-06 起内部改用 `MutableStateFlow` 承载：`observe` 要能**随写入推送**，
 * 才能覆盖「设置页改阈值 → 统计页 C-4 清单即时变化」这条联动（FR-47）。
 */
class FakeConfigDao(initial: Map<String, String> = emptyMap()) : ConfigDao {

    private val entries = MutableStateFlow(initial)

    fun snapshot(): Map<String, String> = entries.value

    override suspend fun get(key: String): String? = entries.value[key]

    override fun observe(key: String): Flow<String?> = entries.map { it[key] }

    override suspend fun put(item: AppConfigEntity) {
        entries.value = entries.value + (item.key to item.value)
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
