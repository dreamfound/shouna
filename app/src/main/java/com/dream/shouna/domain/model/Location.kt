package com.dream.shouna.domain.model

/**
 * 位置。F1 只有 1 条内置哨兵常量（[com.dream.shouna.data.memory.BuiltInData.UNSPECIFIED_LOCATION]），
 * 无位置 UI、无 `LocationRepository`、无层级代码路径（ARCHITECTURE §0 / §3.3）。
 */
data class Location(
    val id: String,
    val name: String,
    /** 内置常量标记。 */
    val isBuiltIn: Boolean,
    /** 未决事项：哨兵父级归属未定，F1 置 null（ARCHITECTURE §7.3）。 */
    val parentId: String? = null,
)
