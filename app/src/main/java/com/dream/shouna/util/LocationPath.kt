package com.dream.shouna.util

import com.dream.shouna.domain.model.Location

/**
 * 位置路径拼装（FR-02 / ARCHITECTURE-P0 §0）：面包屑是**派生**结果，不入库、不物化。
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
}
