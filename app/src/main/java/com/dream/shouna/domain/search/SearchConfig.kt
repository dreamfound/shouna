package com.dream.shouna.domain.search

/**
 * 搜索配置（ARCHITECTURE §4 F1-04 ④）：只留 limit 与权重常量。
 * debounce 时长属 F1-04 ③ 的输入即搜参数，落在 `SearchViewModel`。
 */
data class SearchConfig(
    val limit: Int = 50,
    val nameWeight: Int = 2,
    val categoryWeight: Int = 1,
)
