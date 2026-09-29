package com.dream.shouna.domain.search

/**
 * 命中类型（ARCHITECTURE-P0 §4 P0-04 ④）。
 *
 * F1 只有名称与分类名两档；P0 补拼音与位置两档。
 * 仅在名称命中时才有可高亮区间 —— 其余三档都不对应名称上的连续片段。
 */
enum class MatchType {
    /** 名称子串命中（唯一可高亮的档）。 */
    NAME,

    /** FR-20：名称的全拼或首字母命中（如 `dfs` / `dianfengshan` 命中「电风扇」）。 */
    PINYIN,

    /** FR-02：位置路径或位置名拼音命中。 */
    LOCATION,

    /** 分类名命中。 */
    CATEGORY,
}
