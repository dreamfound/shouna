package com.dream.shouna.domain.model

/**
 * 位置树的一行（ARCHITECTURE-P0 §2「domain 新增 1 个派生模型」）：由 [Location] + 层次 + 路径 +
 * 件数派生，**不落库**。
 *
 * 为什么用「展平的行」而不是嵌套树：Compose 的 `LazyColumn` 只能渲染扁平列表，
 * 折叠/展开交给 UI 持有的 `expandedIds` 过滤 —— 这是「树 → 列表 + 展开集」的常规投影，
 * 也让 VM 只暴露一个 `List` 而不必递归组合。
 */
data class LocationTreeRow(
    val location: Location,
    /** 根级 = 0。 */
    val depth: Int,
    /** 形如「家 › 储物间 › 纸箱-07」，由 [com.dream.shouna.util.LocationPath] 派生。 */
    val pathText: String,
    /** **直属**（且非 `gone`）物品件数。 */
    val itemCount: Int,
    /**
     * P1-02（FR-21）：**含子层**物品件数（本层 + 全部子孙，`gone` 不计）。
     *
     * 树行同时展示「本层 N 件 / 含子层共 M 件」（P1 §4 P1-02 ④）；递归口径见 P1 §8.1-7
     * ——FR-21 计数、FR-28 批量确认、FR-22 位置筛选**一律含子层**。
     * 骨架期默认 0：由 `buildTreeRows` 在 P1-02 实现期改为真实聚合。
     */
    val subtreeItemCount: Int = 0,
    /** 是否有子位置 → 决定是否显示展开箭头。 */
    val hasChildren: Boolean,
)
