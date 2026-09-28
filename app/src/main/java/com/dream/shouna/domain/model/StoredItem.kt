package com.dream.shouna.domain.model

/**
 * F1 唯一实写的领域模型（ARCHITECTURE §3.2）。纯 Kotlin data class，不依赖 Android 框架。
 */
data class StoredItem(
    /** UUID，`IdGenerator.newId()` 生成，源内唯一。 */
    val id: String,
    /** 名称，允许同名。 */
    val name: String,
    /** 归一化名称（全角→半角、大小写、空白折叠），检索用。 */
    val normalizedName: String,
    /** 置空：F1 恒为空串（无别名字段来源）。 */
    val aliasBlob: String = "",
    /** 兜底：F1 恒 = `BuiltInData.UNSPECIFIED_LOCATION_ID`。 */
    val locationId: String,
    /** 未填 = 未分类。 */
    val categoryId: String? = null,
    /** 兜底：F1 恒 = `ItemStatus.IN_STORAGE`。 */
    val status: ItemStatus = ItemStatus.IN_STORAGE,
    /** 置空：无 UI，恒 1。 */
    val quantity: Int = 1,
    /** F1 唯一可选字段（折叠）。 */
    val note: String? = null,
    /** epoch millis UTC。 */
    val createdAt: Long,
    /** epoch millis UTC。 */
    val lastModifiedAt: Long,
    /** 详情页展示、FR-26 写入；未确认 = null。 */
    val lastConfirmedAt: Long? = null,
)
