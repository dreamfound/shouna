package com.dream.shouna.util

import com.dream.shouna.domain.model.Location

/**
 * 位置路径拼装（FR-02 / ARCHITECTURE-P0 §0 + P1-01）：本类持有**两种**路径，别混。
 *
 * | 路径 | 形态 | 是否落库 | 用途 |
 * |---|---|---|---|
 * | 名称路径（[segments] / [format] / [textOf] / [textsOf]） | `家 › 储物间 › 纸箱-07` | **否**，构建时由父链派生 | 面包屑展示、检索维度 |
 * | ID 序列路径（[buildIdPath] / [isDescendantPath]） | `/home/storage/box/` | **是**，落 `location.path` | 子树范围查询（FR-21 / 28 / 22） |
 *
 * 纯函数、无 Android 依赖 → 可在 JVM 单测里直接覆盖（§4 P0-02 ②）。
 *
 * 两个防御取向（数据异常时不崩、只降级）：
 * - **父级缺失**（脏数据 / 并发删除）→ 从断点截断，返回已能拼出的部分；
 * - **成环**（理论上被 §3.4 不变量 3 挡在写入口，此处仍兜底）→ 遇到重复节点即停止。
 */
object LocationPath {

    /** 面包屑分隔符。长按复制时一并使用，保证「看到的就是复制的」。 */
    const val SEPARATOR: String = " › "

    /** 从根到 [locationId] 的节点序列（含自身）。 */
    fun segments(locationId: String, byId: Map<String, Location>): List<Location> {
        val chain = ArrayDeque<Location>()
        val visited = HashSet<String>()
        var cursor: String? = locationId
        while (cursor != null) {
            if (!visited.add(cursor)) break
            val node = byId[cursor] ?: break
            chain.addFirst(node)
            cursor = node.parentId
        }
        return chain.toList()
    }

    /** 把节点序列拼成展示文本。 */
    fun format(segments: List<Location>): String = segments.joinToString(SEPARATOR) { it.name }

    /** 单个位置的路径文本。 */
    fun textOf(locationId: String, byId: Map<String, Location>): String =
        format(segments(locationId, byId))

    /**
     * 一次性算出全部位置的路径文本。
     * 比逐个调用 [textOf] 少一次 `associateBy`，且保证同一批数据内口径一致。
     */
    fun textsOf(locations: List<Location>): Map<String, String> {
        val byId = locations.associateBy { it.id }
        return locations.associate { it.id to textOf(it.id, byId) }
    }

    // --- P1-01：ID 序列路径（物化列 `location.path` 的唯一口径） -------------------------
    // 与上面「名称路径」并列但**用途完全不同**：名称路径是给人看的面包屑、永不落库；
    // ID 序列路径是给 SQL 用的子树范围键、落 `location.path` 列（P1 §3.2、§8.1-2）。

    /**
     * 由父节点的 ID 序列路径拼出自身路径。
     *
     * 形态：含自身、前后带 `/` —— `/` + 根id + `/` + … + 自身id + `/`。
     * [parentPath] 为根级时传**空串**；非空时会做一次「末尾补 `/`」的兜底，
     * 免得调用方一处漏了分隔符就产出拼接不上的路径。
     *
     * 调用方：`SeedCallback`（建库种子，经 `BuiltInData.SEED_LOCATION_PATHS`）、
     * `LocationRepositoryImpl.create`（新建节点）、`MIGRATION_1_2` 的 SQL 是同口径的另一实现。
     */
    fun buildIdPath(selfId: String, parentPath: String): String {
        val prefix = when {
            // 根级（父路径为空串）：前缀就是分隔符本身，产出 `/自身id/`。
            // **不能**退化成空串 —— 那样根节点会得到 `自身id/`（少了前导 `/`），
            // 与 `MIGRATION_1_2` 的 `'/' || id || '/'` 形态不一致，正是 §3.2 要求避免的
            // 「新装库与升级库长得不一样」。
            parentPath.isEmpty() -> ID_PATH_SEPARATOR
            parentPath.endsWith(ID_PATH_SEPARATOR) -> parentPath
            else -> parentPath + ID_PATH_SEPARATOR
        }
        return prefix + selfId + ID_PATH_SEPARATOR
    }

    /**
     * [candidate] 是否位于以 [ancestorPath] 为根的子树内（**含相等**）。
     *
     * 用于 P1-02 的「禁止把节点移入自身子树」前置校验（§3.4-8）：因为 ID 序列路径自带
     * 收尾分隔符，整段前缀匹配不会把 `/a/` 与 `/ab/` 混为一谈（这正是选整段 ID 而非裸 id
     * 拼接的理由）。
     */
    fun isDescendantPath(candidate: String, ancestorPath: String): Boolean =
        candidate == ancestorPath || (ancestorPath.isNotEmpty() && candidate.startsWith(ancestorPath))

    /** ID 序列路径的分隔符。 */
    const val ID_PATH_SEPARATOR: String = "/"
}
