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

    // --- P1-02（FR-04 / 06 / 21 / 28）：位置树深化 ----------------------------------------

    override suspend fun move(nodeId: String, newParentId: String?): Boolean = transactionRunner.run {
        val node = locationDao.findById(nodeId) ?: return@run false
        // 内置哨兵不参与移动（`is_built_in` 的位置本是系统保留位，§2-9）。
        if (node.isBuiltIn) return@run false

        val newParent = newParentId?.let { id -> locationDao.findById(id) ?: return@run false }
        if (newParent != null) {
            if (newParent.isBuiltIn) return@run false
            // §3.4-8：禁止移入自身或自身子树。用 ID 序列路径前缀判定 —— 整段匹配
            // （`/a/` vs `/ab/`）不会误判，这是选「ID 序列」而非裸 id 拼接的直接理由。
            // `newParent == node` 时两者路径相等，`isDescendantPath` 的「含相等」即把自移挡下。
            if (LocationPath.isDescendantPath(candidate = newParent.path, ancestorPath = node.path)) {
                return@run false
            }
        }

        relocate(node = node, newParent = newParent)
        true
    }

    override suspend fun merge(sourceId: String, targetId: String): Boolean = transactionRunner.run {
        val source = locationDao.findById(sourceId) ?: return@run false
        val target = locationDao.findById(targetId) ?: return@run false
        if (source.isBuiltIn || target.isBuiltIn) return@run false
        if (sourceId == targetId) return@run false
        // 目标位于 source 子树内 → 改挂自己会造成自环（与 move 同一条前置校验）。
        if (LocationPath.isDescendantPath(candidate = target.path, ancestorPath = source.path)) {
            return@run false
        }

        // ① 子位置整棵改挂 target —— 逐个走 [relocate]，与移动共用同一条路径重写逻辑。
        locationDao.childrenOf(sourceId).forEach { child ->
            relocate(node = child, newParent = target)
        }

        // ② source 的直属物品改挂 target。状态不动；「位置变动」按 §3.5 矩阵刷 `last_modified_at`。
        itemDao.moveItems(sourceId = sourceId, targetId = targetId, modifiedAt = timeUtil.nowMillis())

        // ③ source 此时既无子位置（①已改挂）也无物品（②已迁移）→ 直接删即无孤儿。
        //    未走「删除两档」是刻意的：那两档解决的是「物品往哪去」，此处物品已有去处。
        locationDao.delete(sourceId)
        true
    }

    override suspend fun setTemporary(nodeId: String, flag: Boolean): Boolean {
        val node = locationDao.findById(nodeId) ?: return false
        if (node.isBuiltIn) return false
        // 位置侧操作，不触碰任何物品时间戳（P1 §3.5）。
        return locationDao.setTemporary(id = nodeId, flag = flag) > 0
    }

    override fun observeCounts(nodeId: String): Flow<LocationCounts> =
        // 复用与 `observeTree` 同一对数据源：树行与「含子层共 M 件」由此天然同源，
        // 不会出现「树行说 3 件、下钻列表显示 4 件」这种自相矛盾。
        combine(locationDao.observeAll(), itemDao.observeActiveCounts()) { rows, counts ->
            val directById = counts.associate { it.locationId to it.itemCount }
            val all = rows.map { it.toDomain() }
            LocationCounts(
                nodeId = nodeId,
                directCount = directById[nodeId] ?: 0,
                subtreeCount = subtreeItemCounts(all, directById)[nodeId] ?: 0,
            )
        }

    override fun observeLocationCount(): Flow<Int> = locationDao.observeUsableCount()

    /**
     * 把 [node] 整棵子树挂到 [newParent] 下：改自身 `parent_id` + 重写**全部子孙**的 `path`。
     *
     * 调用方必须已在事务内并完成前置校验（内置、自身/子树、目标存在）。父级未变时直接返回
     * —— 无谓的重写会白跑一次子树遍历。
     */
    private suspend fun relocate(node: LocationEntity, newParent: LocationEntity?) {
        if (node.parentId == newParent?.id) return

        val oldPath = node.path
        val newPath = LocationPath.buildIdPath(selfId = node.id, parentPath = newParent?.path.orEmpty())
        // 旧路径为空说明该行是脏数据（迁移应已回填）→ 子孙无法按前缀定位，只改父级与自身。
        val descendants = if (oldPath.isEmpty()) {
            emptyList()
        } else {
            locationDao.descendantsOf(prefix = oldPath, excludeId = node.id)
        }

        locationDao.updateParentAndPath(id = node.id, parentId = newParent?.id, path = newPath)
        descendants.forEach { row ->
            // 子孙新路径 = 自身新路径 + （旧路径去掉旧前缀的尾部）。ID 序列路径自带收尾 `/`，
            // 故 `removePrefix` 的结果一定以 id + `/` 开头，不会少或多一个分隔符。
            locationDao.updatePath(id = row.id, path = newPath + row.path.removePrefix(oldPath))
        }
    }

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
    // P1-02（FR-21）：本层 + 全部子孙的件数，与 `observeCounts` 共用同一纯函数 ⇒ 两处口径不可能漂移。
    val subtreeById = subtreeItemCounts(locations, itemCountById)
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
                    subtreeItemCount = subtreeById[node.id] ?: (itemCountById[node.id] ?: 0),
                    hasChildren = (childCountByParent[node.id] ?: 0) > 0,
                )
                appendChildren(node.id, depth + 1)
            }
    }

    appendChildren(null, 0)
    return rows
}

/**
 * 各节点的「含子层件数」（P1-02，FR-21）：`节点自身直属数 + 全部子孙递归和`。
 *
 * 纯函数、与数据源无关。之所以在内存里做而不发一条 `path` 前缀 SQL：树行渲染本就要
 * 加载整棵树与全量直属计数（`observeTree` 的两个数据源），再打一次库是重复 IO；
 * 位置上量级（NFR-07 的 500 节点）下这一次遍历的代价可忽略。
 * 需要**单点**计数的调用方（`observeCounts`）也用同一函数，保证两处数字恒等。
 *
 * 防御：脏数据成环时 `computing` 集合让递归就地返回 0 而非死循环。
 */
internal fun subtreeItemCounts(
    locations: List<Location>,
    directCountById: Map<String, Int>,
): Map<String, Int> {
    val childrenByParent = locations.groupBy { it.parentId }
    val result = HashMap<String, Int>(locations.size)
    val computing = HashSet<String>()

    fun rollup(node: Location): Int {
        result[node.id]?.let { return it }
        if (!computing.add(node.id)) return 0
        val total = (directCountById[node.id] ?: 0) +
            childrenByParent[node.id].orEmpty().sumOf { rollup(it) }
        computing.remove(node.id)
        result[node.id] = total
        return total
    }

    locations.forEach { rollup(it) }
    return result
}
