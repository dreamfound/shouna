package com.dream.shouna.data.memory

import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.Location

/**
 * 内置常量（ARCHITECTURE §3.3）：位置哨兵 1 条 + 内置分类 8 条，F1 只读。
 * 不变量（§3.4）：哨兵只存在 1 条；分类恰好 8 条且 id 互不重复。
 */
object BuiltInData {
    /** 位置哨兵 id。`StoredItem.locationId` 恒等于它且永不为空串。 */
    const val UNSPECIFIED_LOCATION_ID: String = "builtin-location-unspecified"

    /** 内置分类条数（单测断言用）。 */
    const val BUILT_IN_CATEGORY_COUNT: Int = 8

    /** 位置哨兵：名称「未指定位置」、`isBuiltIn = true`、无父级。 */
    val UNSPECIFIED_LOCATION: Location = Location(
        id = UNSPECIFIED_LOCATION_ID,
        name = "未指定位置",
        isBuiltIn = true,
        parentId = null,
    )

    /**
     * 8 条内置分类常量，F1 只读（§3.3）。
     * 调用关系：8 × `Category(id, name, sortOrder)` 构造；被 `CategoryRepositoryImpl.observeCategories` 消费。
     * 取值口径（常量兜底，ARCHITECTURE §7.3 未钉死具体名单）：名称取自 PRD `FR-12` 的举例
     * 「电器/衣物/工具/文具/药品/其他…」并补足到 8 条；`sortOrder` 即 chips 排列顺序。
     */
    val BUILT_IN_CATEGORIES: List<Category> = listOf(
        Category(id = "builtin-category-appliance", name = "电器", sortOrder = 1),
        Category(id = "builtin-category-clothing", name = "衣物", sortOrder = 2),
        Category(id = "builtin-category-tool", name = "工具", sortOrder = 3),
        Category(id = "builtin-category-stationery", name = "文具", sortOrder = 4),
        Category(id = "builtin-category-medicine", name = "药品", sortOrder = 5),
        Category(id = "builtin-category-food", name = "食品", sortOrder = 6),
        Category(id = "builtin-category-daily", name = "日用", sortOrder = 7),
        Category(id = "builtin-category-other", name = "其他", sortOrder = 8),
    )

    /**
     * 全部内置位置（F1 仅 1 条哨兵）。
     * 调用关系：`listOf(UNSPECIFIED_LOCATION)`；被 `StoredItem.locationId` 兜底引用（§3.4 不变量 1）。
     */
    val BUILT_IN_LOCATIONS: List<Location> = listOf(UNSPECIFIED_LOCATION)
}
