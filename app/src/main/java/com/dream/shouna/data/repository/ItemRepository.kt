package com.dream.shouna.data.repository

import com.dream.shouna.domain.model.ItemDetail
import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.domain.model.StoredItem
import com.dream.shouna.domain.search.SearchDoc
import kotlinx.coroutines.flow.Flow

/**
 * 物品仓库（ARCHITECTURE §2）：用例编排、派生字段（归一化名 / 检索键）、失效过滤。
 * 只暴露稳定领域类型，不泄漏内存源内部结构。
 */
interface ItemRepository {
    /**
     * FR-09 / FR-10 / FR-12 + **位置必填**（ARCHITECTURE-P0 §0）：必填项 = **名称 + 位置**，
     * 状态恒 `in_storage`。
     *
     * 位置非空由 UI 层 `QuickAddUiState.canSave` 门控（用户要求「必须手动选择或输入位置」）；
     * 仓库层不做二次校验，只保证「写入的 `location_id` 一定是调用方给的真实位置」。
     * 写入成功同时刷新该位置的 `last_used_at`（FR-11 的预选数据源）。
     */
    suspend fun createItemQuick(
        name: String,
        categoryId: String?,
        note: String?,
        locationId: String,
    ): StoredItem

    /** 首页 `isEmpty` 判定用的条目摘要流。 */
    fun observeItemSummaries(): Flow<List<StoredItem>>

    /** 搜索结果文档流（F1-04 索引输入；P0-04 起含拼音、位置路径与确认时间）。 */
    fun observeSearchDocs(): Flow<List<SearchDoc>>

    /**
     * FR-23：最近搜索词流（按最近使用倒序，上限 20 条），**仅搜索页空态**展示。
     *
     * 【归属说明】`ARCHITECTURE-P0` §2 只定了 3 个仓库（Item / Location / Category），
     * 未给 `recent_search` 指定归属。它落在本接口，理由是「搜索页已经依赖本接口」
     * （[observeSearchDocs] 是同一页的数据源），加一个只有两个方法的仓库反而多一层空壳。
     */
    fun observeRecentQueries(): Flow<List<String>>

    /**
     * FR-23：记录一次**有效检索**（结果非空才由调用方写入）。
     *
     * 去重口径 = 归一化后的词（`recent_search.normalized_query` 唯一索引）；写入后把表裁剪到
     * 上限 20 条。空词 / 纯空白不记。
     */
    suspend fun recordRecentQuery(query: String)

    suspend fun getItemDetail(itemId: String): ItemDetail?

    /** FR-26：只写 `lastConfirmedAt`，不动 `lastModifiedAt`。 */
    suspend fun confirmItem(itemId: String): StoredItem?

    /**
     * FR-25：切换状态。写 `status` 与 `lastModifiedAt`（§3.5 矩阵），**不动** `lastConfirmedAt`
     * ——「不在了」不是一次确认。未命中返回 null。
     */
    suspend fun setStatus(itemId: String, status: ItemStatus): StoredItem?

    /** FR-25：`gone` → `in_storage` 的恢复入口（语义化包装，避免调用方拼 `setStatus`）。 */
    suspend fun restore(itemId: String): StoredItem?

    // --- P1-03 / 04 / 06：编辑、批量确认、查重 ---------------------------------------

    /**
     * P1-04：**补丁式**写入（分类 / 别名 / 数量 / 备注）。只写被显式给出的字段，其余原样。
     *
     * 时间戳口径（P1 §3.5 / §3.4-10）：别名与数量的变化**不刷新** `last_modified_at`
     * ——它们不改变「东西在哪、还在不在」这个身份。
     * 别名变更时**同事务重算** `pinyin_full` / `pinyin_initial`（别名与名称同档，参检索）。
     *
     * 返回更新后的记录；未命中返回 null。
     */
    suspend fun updateFields(itemId: String, patch: ItemFieldPatch): StoredItem?

    /**
     * FR-28：对该位置**含子层**的全部物品一次性确认（写 `last_confirmed_at`，不动 `last_modified_at`）。
     * 返回受影响行数 —— 调用方据此向用户回报「本次确认了 N 件」（P1 §3.5 矩阵末行）。
     */
    suspend fun confirmByLocation(nodeId: String): Int

    /**
     * FR-14：录入时的重名 / 高度相似查重，供 P-ADD 的**非阻塞**提示使用（不阻断保存）。
     *
     * 口径（P1 §8.1-11）：「同名」= 归一化名完全相同；「高度相似」= 互为子串且长度差 ≤ 2。
     * **不做编辑距离**（误报率高、纯增成本）。
     */
    suspend fun findSimilar(name: String): List<StoredItem>
}

/**
 * 补丁式写入的载荷（P1-04）。`null` = 「不改这一项」，因此**无法用它把备注清空为 null**
 * ——清空备注走空串再归一（与录入页 `note?.trim()?.ifBlank { null }` 同一口径）。
 *
 * 注：P1 §2 的表述里还提到「去向备注」，但 §3.1 已裁定本次**不新增列**，
 * `item` 表也没有对应字段 → 骨架期不并入本补丁，该字段的落点待裁决后再定。
 */
data class ItemFieldPatch(
    val categoryId: String? = null,
    val aliases: List<String>? = null,
    val quantity: Int? = null,
    val note: String? = null,
)
