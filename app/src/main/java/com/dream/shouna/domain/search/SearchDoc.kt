package com.dream.shouna.domain.search

import com.dream.shouna.domain.model.ItemStatus

/**
 * 索引文档（ARCHITECTURE-P0 §4 P0-04 ②）：由 `item` + `category` + `location` 三表联合派生。
 *
 * 检索维度（权重从高到低，见 [SearchConfig]）：
 * 名称子串 → **拼音全拼 / 首字母** → 位置路径（含位置名拼音）→ 分类名。
 *
 * 后三个字段的持久化口径不同，别混：
 * - [pinyinFull] / [pinyinInitial]：**持久化列**读出（`item.pinyin_full` 等，A-2 允许）；
 * - [pinyinLocationPath]：**构建时实时派生**、不落库（守 A-2：位置改名与移动的代价与物品数脱钩）。
 */
data class SearchDoc(
    val itemId: String,
    val name: String,
    val normalizedName: String,
    /** FR-20：名称全拼（如 `dianfengshan`）。 */
    val pinyinFull: String = "",
    /** FR-20：名称拼音首字母（如 `dfs`）。 */
    val pinyinInitial: String = "",
    val categoryId: String?,
    val categoryName: String?,
    val normalizedCategoryName: String?,
    /** FR-02：位置路径展示文本（「家 › 储物间 › 纸箱-07」）。 */
    val locationPath: String = "",
    /** 位置路径的归一化文本（检索比较用；[locationPath] 是展示用原样）。 */
    val normalizedLocationPath: String = "",
    /** 位置路径的全拼（实时派生，不落库）。 */
    val pinyinLocationPath: String = "",
    val status: ItemStatus,
    /** 指纹计算用（size + 最大 lastModifiedAt）。 */
    val lastModifiedAt: Long,
    /** FR-27：结果行的「最后确认」与超期判定输入。 */
    val lastConfirmedAt: Long? = null,
)
