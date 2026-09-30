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
    /**
     * 别名的**持久化形态**：多个别名用 `\u001F` 拼接（`item.alias_blob`）。
     * P0 恒为空串；P1-04 起由物品编辑页写入。
     */
    val aliasBlob: String = "",
    /**
     * P1-04：别名的**领域形态**（[aliasBlob] 的解码结果），参与检索、**与名称同档**。
     *
     * 与 [aliasBlob] 同源、不各存一份：写入时由本列表编码回 `alias_blob`。
     * 上限 5 个、按归一化值去重、不允许与名称相同（P1 §8.1-4）。
     */
    val aliases: List<String> = emptyList(),
    /** 兜底：F1 恒 = `BuiltInData.UNSPECIFIED_LOCATION_ID`。 */
    val locationId: String,
    /** 未填 = 未分类。 */
    val categoryId: String? = null,
    /** 兜底：F1 恒 = `ItemStatus.IN_STORAGE`。 */
    val status: ItemStatus = ItemStatus.IN_STORAGE,
    /** 整数、≥ 1、默认 1、无单位；P0 恒 1，P1-04 起可改（`prd/11` Q3）。 */
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
