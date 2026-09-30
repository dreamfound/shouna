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
     *
     * 时间戳（§3.5，2026-09-30 修订）：`createdAt` / `lastModifiedAt` / `lastConfirmedAt`
     * **取同一次 now** —— 录入即一次确认，新物品不会立刻被判超期。
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
     * 别名变更时**同一条语句内**一并重算 `pinyin_full` / `pinyin_initial`（别名与名称同档，
     * 参检索）；因为落在一条 UPDATE 里，不需要事务包裹（守 `实现约束.md` §2-5）。
     *
     * 返回更新后的记录；未命中返回 null。空补丁不改任何列，直接回当前记录。
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

    // --- P1-03：归纳统计页的三块数据（C-1 / C-4 / C-5） --------------------------------

    /**
     * P1-03（C-5 / FR-38）：**归位到指定位置** —— 把物品落到 [locationId] 并把状态改回「在存放中」。
     *
     * 一次操作同时完成「换位置」与「离开待归位」：`to_be_put_back` 的物品本就该有个去处，
     * 让用户先挑位置再一步到位，比「先归位、再单独移过去」少一次交互。
     * `last_modified_at = now`（§3.5 矩阵：状态变化属变动）；未命中返回 null。
     */
    suspend fun putBack(itemId: String, locationId: String): StoredItem?

    /**
     * P1-04：把物品**移动**到另一个位置（详情页折叠区的「移动到…」）。不改状态、不写确认时间。
     */
    suspend fun moveItem(itemId: String, locationId: String): StoredItem?

    /**
     * P1-03（C-1 第一块）：物品总数 = 非 `gone`（即 `in_storage` + `to_be_put_back`）。
     */
    fun observeActiveItemCount(): Flow<Int>

    /**
     * P1-03（C-4 / FR-29）：超期未确认清单 —— 活跃且 `last_confirmed_at IS NULL` 或早于
     * `now - thresholdMonths 个月`（含「从未确认」）。排序为「最久未确认在前」。
     *
     * 阈值以**参数**进入（而不是在仓库里再读一次 `app_config`）：设置页改阈值后
     * `StatsViewModel` 重新订阅即可即时生效，仓库不必自己持有一条配置流。
     */
    fun observeOverdueItems(thresholdMonths: Int): Flow<List<StoredItem>>

    /**
     * P1-03（C-5 / FR-38）：待归位清单 —— 状态为 `to_be_put_back` **∪** 位于用户标记过的临时位置。
     *
     * 两部分**按物品去重**（P1 §8.1-9）：一条物品同时满足两者也只出现一次。
     * 「临时位置」部分只在存在被标记过的临时位置时才非空。
     */
    fun observeToBePutBackItems(): Flow<List<StoredItem>>
}

/**
 * 补丁式写入的载荷（P1-04）。`null` = 「不改这一项」。
 *
 * **置空有两处不对称，务必看清**（这是本类型最容易写错的地方）：
 * - **备注**：`null` = 不改；要清空就传**空串 / 纯空白**，由仓库归一为 `null`
 *   （与录入页 `note?.trim()?.ifBlank { null }` 同一口径）。
 * - **分类**：`categoryId` 本身可空，`null` 已被「不改」占用 → 置为「未分类」必须显式置
 *   [clearCategory] = true。两者互斥：`clearCategory = true` 时 [categoryId] 须为 `null`。
 *
 * 注：P1 §2 的表述里还提到「去向备注」，但 §3.1 已裁定本次**不新增列**，
 * `item` 表也没有对应字段 → 不并入本补丁（与 `ItemFieldPatch` 的原始形态一致）。
 */
data class ItemFieldPatch(
    val categoryId: String? = null,
    /** 是否把分类置空（回落「未分类」）。与 [categoryId] 互斥，见类注释。 */
    val clearCategory: Boolean = false,
    val aliases: List<String>? = null,
    val quantity: Int? = null,
    val note: String? = null,
)
