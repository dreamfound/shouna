package com.dream.shouna.data.repository

import com.dream.shouna.data.local.TransactionRunner
import com.dream.shouna.data.local.dao.ItemDao
import com.dream.shouna.data.local.dao.LocationDao
import com.dream.shouna.data.local.entity.LocationEntity
import com.dream.shouna.data.memory.BuiltInData
import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.domain.model.Location
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.domain.model.StoredItem
import com.dream.shouna.util.IdGenerator
import com.dream.shouna.util.LocationPath
import com.dream.shouna.util.TimeUtil
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * 位置树实现（ARCHITECTURE-P0 §2 / §4 P0-02）。
 *
 * 三条硬约束的落点：
 * - **跨表写在单事务内**：删除子树涉及「迁移物品 + 删位置」多个语句，必须原子（§2 写路径守卫）。
 * - **自底向上删**：先处理叶子，父级删除时其子行已不存在 → 满足 `parent_id` 的 FK RESTRICT。
 * - **内置哨兵不外露**：所有对外读取都过滤 `is_built_in`；哨兵只作为「档 2 的物品收容位」被写入。
 *
 * 被调用方：`QuickAddViewModel`（预选最近位置 / 新建位置 / touch）、`LocationBrowseViewModel`、
 *            `LocationPickerSheet` 的数据源。
 */
@Singleton
class LocationRepositoryImpl @Inject constructor(
    private val transactionRunner: TransactionRunner,
    private val locationDao: LocationDao,
    private val itemDao: ItemDao,
    private val idGenerator: IdGenerator,
    private val timeUtil: TimeUtil,
) : LocationRepository {

    override fun observeTree(): Flow<List<LocationTreeRow>> =
        combine(locationDao.observeAll(), itemDao.observeActiveCounts()) { rows, counts ->
            val countById = counts.associate { it.locationId to it.itemCount }
            buildTreeRows(
                locations = rows.map { it.toDomain() }.filterNot { it.isBuiltIn },
                itemCountById = countById,
            )
        }

    override fun observeChildren(parentId: String?): Flow<List<Location>> =
        locationDao.observeChildren(parentId).map { rows ->
            rows.map { it.toDomain() }.filterNot { it.isBuiltIn }
        }

    override fun observeItemsIn(locationId: String): Flow<List<StoredItem>> =
        itemDao.observeByLocation(locationId).map { rows -> filterActive(rows.map { it.toDomain() }) }

    override suspend fun findById(id: String): Location? = locationDao.findById(id)?.toDomain()

    override suspend fun create(name: String, parentId: String?): Location =
        transactionRunner.run {
            // 同级末尾排序：并列时按名称，保证 UI 顺序稳定。
            val nextOrder = (locationDao.siblings(parentId).maxOfOrNull { it.sortOrder } ?: 0) + 1
            val id = idGenerator.newId()
            // P1-01：`path` 由**父节点的 ID 序列路径**派生（根级 = 空父路径）。
            // 口径与建库种子、迁移回填共用 [LocationPath.buildIdPath]（P1 §3.2）。
            val parentPath = parentId?.let { locationDao.findById(it)?.path }.orEmpty()
            val location = Location(
                id = id,
                name = name.trim(),
                isBuiltIn = false,
                parentId = parentId,
                sortOrder = nextOrder,
            )
            locationDao.insert(
                location.toEntity(
                    now = timeUtil.nowMillis(),
                    path = LocationPath.buildIdPath(selfId = id, parentPath = parentPath),
                ),
            )
            location
        }

    override suspend fun rename(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        // 改名不动物品记录：路径由父链实时拼装，无冗余可修（§1 FR-03）。
        return locationDao.rename(id, trimmed) > 0
    }

    override suspend fun setNote(id: String, note: String?): Boolean =
        locationDao.setNote(id, note?.trim()?.ifBlank { null }) > 0

    override suspend fun delete(
        id: String,
        mode: LocationDeleteMode,
        migrateTargetId: String?,
    ): Boolean = transactionRunner.run {
        val target = locationDao.findById(id) ?: return@run false
        // 内置哨兵不可删（§3.4 不变量 3）。
        if (target.isBuiltIn) return@run false

        val destinationId: String = when (mode) {
            LocationDeleteMode.ARCHIVE -> BuiltInData.UNSPECIFIED_LOCATION_ID
            LocationDeleteMode.MIGRATE -> {
                val requested = migrateTargetId ?: return@run false
                // 拒绝：不存在 / 指向自身 / 指向自身子孙（后者会造成环或「删了又留」）。
                if (locationDao.findById(requested) == null) return@run false
                if (requested == id || isInSubtree(candidateId = requested, rootId = id)) {
                    return@run false
                }
                requested
            }
        }

        val now = timeUtil.nowMillis()
        // 自底向上：先叶子后根，父级删除时其子行已不存在 → FK RESTRICT 不会挡。
        val subtreeIds = collectSubtreeIdsBottomUp(rootId = id)

        subtreeIds.forEach { locationId ->
            when (mode) {
                // 档 2：物品标记 gone 并收容到哨兵（location_id 非空，必须给去处）。
                LocationDeleteMode.ARCHIVE -> itemDao.moveItemsAndSetStatus(
                    sourceId = locationId,
                    targetId = destinationId,
                    status = ItemStatus.GONE,
                    modifiedAt = now,
                )

                // 档 1：物品整体迁移，状态不动。
                LocationDeleteMode.MIGRATE -> itemDao.moveItems(
                    sourceId = locationId,
                    targetId = destinationId,
                    modifiedAt = now,
                )
            }
        }

        subtreeIds.forEach { locationDao.delete(it) }
        true
    }

    override suspend fun recentUsed(limit: Int): List<Location> =
        locationDao
            .recentUsed(excludeId = BuiltInData.UNSPECIFIED_LOCATION_ID, limit = limit)
            .map { it.toDomain() }

    override suspend fun touchLastUsed(id: String) {
        locationDao.touchLastUsed(id = id, at = timeUtil.nowMillis())
    }

    // --- P1-02（FR-04 / 06 / 21 / 28）：位置树深化（骨架，桩体待实现） ------------------

    override suspend fun move(nodeId: String, newParentId: String?): Boolean =
        TODO("P1-02 ①: 校验 isDescendantPath → 同事务改 parent_id + 重写子树 path")

    override suspend fun merge(sourceId: String, targetId: String): Boolean =
        TODO("P1-02 ②: 子位置与物品改挂 target → 复用既有删除档位处理 source")

    override suspend fun setTemporary(nodeId: String, flag: Boolean): Boolean =
        TODO("P1-02 ⑤: 写 location.is_temporary（位置侧操作，不刷新物品时间戳）")

    override fun observeCounts(nodeId: String): Flow<LocationCounts> =
        TODO("P1-02 ③: 本层计数 + 子树前缀区间计数的合并流")

    /** 子树 id 收集，返回顺序为**子先于父**（先叶子、后根），供自底向上删除使用。 */
    private suspend fun collectSubtreeIdsBottomUp(rootId: String): List<String> {
        val preorder = ArrayList<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(rootId)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            preorder += current
            locationDao.childrenOf(current).forEach { stack.addLast(it.id) }
        }
        // 前序遍历中父恒先于子 → 反转后父恒后于子。
        return preorder.asReversed()
    }

    /** `candidateId` 是否位于以 `rootId` 为根的子树内（含自身）。 */
    private suspend fun isInSubtree(candidateId: String, rootId: String): Boolean {
        val visited = HashSet<String>()
        var cursor: String? = candidateId
        while (cursor != null) {
            if (!visited.add(cursor)) return false
            if (cursor == rootId) return true
            cursor = locationDao.findById(cursor)?.parentId
        }
        return false
    }
}

/** 持久化行 → 领域模型。 */
internal fun LocationEntity.toDomain(): Location = Location(
    id = id,
    name = name,
    isBuiltIn = isBuiltIn,
    parentId = parentId,
    isTemporary = isTemporary,
    note = note,
    sortOrder = sortOrder,
    lastUsedAt = lastUsedAt,
)

/**
 * 领域模型 → 持久化行。`path` 是物化列（P1-01），**由调用方算好传入** —— 本函数保持纯映射，
 * 不自己去查父级（那样会把一次写变成「读父链 + 写」的两步，破坏「单表写不用事务」的约定）。
 */
internal fun Location.toEntity(now: Long, path: String): LocationEntity = LocationEntity(
    id = id,
    name = name,
    parentId = parentId,
    path = path,
    isBuiltIn = isBuiltIn,
    isTemporary = isTemporary,
    note = note,
    sortOrder = sortOrder,
    lastUsedAt = lastUsedAt,
    createdAt = now,
)

/**
 * 「位置列表 + 件数」→ 展平的树行（深度优先，父在前、其子紧随）。
 * 纯函数：与数据源无关，可在 JVM 单测里直接喂假数据。
 *
 * 不在树上的节点（父级被过滤或缺失）会被丢弃 —— 这是有意的降级：宁可少显示一行，
 * 也不要出现「路径里有不存在的父级」这种自相矛盾的树。
 */
internal fun buildTreeRows(
    locations: List<Location>,
    itemCountById: Map<String, Int>,
): List<LocationTreeRow> {
    val pathTextById = LocationPath.textsOf(locations)
    val childCountByParent = locations.groupingBy { it.parentId }.eachCount()
    val rows = ArrayList<LocationTreeRow>(locations.size)

    fun appendChildren(parentId: String?, depth: Int) {
        locations
            .filter { it.parentId == parentId }
            .sortedWith(compareBy({ it.sortOrder }, { it.name }))
            .forEach { node ->
                rows += LocationTreeRow(
                    location = node,
                    depth = depth,
                    pathText = pathTextById[node.id].orEmpty(),
                    itemCount = itemCountById[node.id] ?: 0,
                    // TODO(P1-02 ③): 由子树前缀区间聚合出「含子层共 M 件」，当前留默认 0（骨架期不展示）。
                    subtreeItemCount = 0,
                    hasChildren = (childCountByParent[node.id] ?: 0) > 0,
                )
                appendChildren(node.id, depth + 1)
            }
    }

    appendChildren(null, 0)
    return rows
}
