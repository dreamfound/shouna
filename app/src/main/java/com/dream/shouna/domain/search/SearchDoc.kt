package com.dream.shouna.domain.search

import com.dream.shouna.domain.model.ItemStatus

/**
 * 索引文档（ARCHITECTURE-P0 §4 P0-04 ② + P1-04）：由 `item` + `category` + `location` 三表联合派生。
 *
 * 检索维度（权重从高到低，见 [SearchConfig]）：
 * 名称子串（**含别名，同档**）→ **拼音全拼 / 首字母** → 位置路径（含位置名拼音）→ 分类名。
 *
 * 后三个字段的持久化口径不同，别混：
 * - [pinyinFull] / [pinyinInitial]：**持久化列**读出（`item.pinyin_full` 等，A-2 允许）；
 * - [pinyinLocationPath]：**构建时实时派生**、不落库（守 A-2：位置改名与移动的代价与物品数脱钩）。
 */
data class SearchDoc(
    val itemId: String,
    val name: String,
    val normalizedName: String,
    /**
     * P1-04：别名（展示用原串）。**与名称同档**（`MatchType.NAME`），不新开档位
     * ——别名存在的意义就是「换个说法也能搜到同一个东西」，另立低档会让它被同名命中挤下去。
     */
    val aliases: List<String> = emptyList(),
    /** P1-04：别名的归一化形态（检索比较用）。 */
    val normalizedAliases: List<String> = emptyList(),
    /** FR-20：名称全拼（如 `dianfengshan`）。 */
    val pinyinFull: String = "",
    /** FR-20：名称拼音首字母（如 `dfs`）。 */
    val pinyinInitial: String = "",
    val categoryId: String?,
    val categoryName: String?,
    val normalizedCategoryName: String?,
    /**
     * P1-06：物品所在位置 id —— FR-22 的**位置筛选**输入。
     *
     * 为什么不能拿 [locationPath] 顶替：那是给人看的**名称路径**（改名即变），
     * 而「含子层」的判定依赖位置树的前缀关系（P1 §8.1-7），必须靠 id 才能落到 `location.path`。
     */
    val locationId: String = "",
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
