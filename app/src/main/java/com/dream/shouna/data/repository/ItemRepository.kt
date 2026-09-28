package com.dream.shouna.data.repository

import com.dream.shouna.domain.model.ItemDetail
import com.dream.shouna.domain.model.StoredItem
import com.dream.shouna.domain.search.SearchDoc
import kotlinx.coroutines.flow.Flow

/**
 * 物品仓库（ARCHITECTURE §2）：用例编排、派生字段（归一化名 / 检索键）、失效过滤。
 * 只暴露稳定领域类型，不泄漏内存源内部结构。
 */
interface ItemRepository {
    /** FR-09 / FR-10 / FR-12：唯一必填 = 名称；位置恒哨兵、状态恒 `in_storage`。 */
    suspend fun createItemQuick(name: String, categoryId: String?, note: String?): StoredItem

    /** 首页 `isEmpty` 判定用的条目摘要流。 */
    fun observeItemSummaries(): Flow<List<StoredItem>>

    /** 搜索结果文档流（F1-04 索引输入）。 */
    fun observeSearchDocs(): Flow<List<SearchDoc>>

    suspend fun getItemDetail(itemId: String): ItemDetail?

    /** FR-26：只写 `lastConfirmedAt`，不动 `lastModifiedAt`。 */
    suspend fun confirmItem(itemId: String): StoredItem?
}
