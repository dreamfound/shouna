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
}
