package com.dream.shouna.domain.search

/**
 * 搜索配置（ARCHITECTURE-P0 §4 P0-04 ④）：limit + 四档命中权重。
 *
 * 权重次序照 PRD 08 §7.x / P0 §4 ④ 的原文 ——
 * 「名称前缀命中 > 名称子串命中 > 别名 / 拼音命中 > 位置 / 分类命中」，
 * 编码为 4 级：`名称 3 > 拼音 2 > 位置 1 = 分类 1`。
 *
 * 两点说明：
 * - **前缀 / 子串不拆档**：F1 已把两者合并进 `NAME` 一档（F1 单测断言 `MatchType.NAME`），
 *   拆档会改动 F1 冻结的单测 → 本次保留合并口径；真要拆时在 3 与 2 之间插一档即可（值 3 / 2 之间还有余量）。
 * - **位置与分类同档**：PRD 原文把「位置 / 分类」并列在同一级（都属「不是很确定的线索」），
 *   故两者取同值；拼音是**近似但指向名称**的输入，单独一档高于它们。
 *
 * debounce 时长属输入即搜参数，落在 `SearchViewModel`。
 */
data class SearchConfig(
    val limit: Int = 50,
    /** 名称子串命中（含前缀；F1 已合并该两档）。 */
    val nameWeight: Int = 3,
    /** FR-20 拼音（全拼或首字母）命中。 */
    val pinyinWeight: Int = 2,
    /** FR-02 位置路径（含位置名拼音）命中。 */
    val locationWeight: Int = 1,
    /** 分类名命中。 */
    val categoryWeight: Int = 1,
)
