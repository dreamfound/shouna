package com.dream.shouna.data.memory

import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.Location

/**
 * 内置常量唯一来源（ARCHITECTURE-P0 §3.3）：本页不再只有哨兵与分类，另含
 * **首启默认位置树 6 节点**（[DEFAULT_LOCATION_TREE]），全部由
 * [com.dream.shouna.data.local.SeedCallback] 在首次建库时写入。
 *
 * 口径区分（务必看清）：
 * - [BUILT_IN_LOCATIONS] = **系统保留位**（哨兵）→ 不出现在选择器／浏览页、不可删。
 * - [DEFAULT_LOCATION_TREE] = **用户可见的初始位置** → 可改名、可删除（删光后仍可「＋ 新建位置」，
 *   不构成无法录入的死锁）。
 *
 * 不变量（§3.4）：哨兵只存在 1 条；分类恰好 8 条且 id 互不重复；默认树 6 节点 id 互不重复。
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
     * 全部内置位置（**仅 1 条哨兵**，系统保留位）。
     * 调用关系：`listOf(UNSPECIFIED_LOCATION)`；被 [com.dream.shouna.data.repository.ItemRepositoryImpl]
     * 的极端兜底与「删位置档 2」的 `gone` 物品收容引用。
     */
    val BUILT_IN_LOCATIONS: List<Location> = listOf(UNSPECIFIED_LOCATION)

    // --- P0 §3.3 种子：首启默认位置树 -------------------------------------------
    // 根级 1 个 + 其下 5 个 = 6 节点。isBuiltIn = false → 用户可改名、可删除。

    /** 默认位置树根节点 id。 */
    const val DEFAULT_ROOT_LOCATION_ID: String = "builtin-location-home"

    /**
     * 首启默认位置树（6 节点）：`家` → 卧室 / 客厅 / 厨房 / 储物间 / 阳台。
     * 被 [com.dream.shouna.data.local.SeedCallback] 消费；种子位置与用户自建位置同权（可改可删）。
     */
    val DEFAULT_LOCATION_TREE: List<Location> = listOf(
        Location(
            id = DEFAULT_ROOT_LOCATION_ID,
            name = "家",
            isBuiltIn = false,
            parentId = null,
            sortOrder = 1,
        ),
        Location(
            id = "builtin-location-bedroom",
            name = "卧室",
            isBuiltIn = false,
            parentId = DEFAULT_ROOT_LOCATION_ID,
            sortOrder = 1,
        ),
        Location(
            id = "builtin-location-living-room",
            name = "客厅",
            isBuiltIn = false,
            parentId = DEFAULT_ROOT_LOCATION_ID,
            sortOrder = 2,
        ),
        Location(
            id = "builtin-location-kitchen",
            name = "厨房",
            isBuiltIn = false,
            parentId = DEFAULT_ROOT_LOCATION_ID,
            sortOrder = 3,
        ),
        Location(
            id = "builtin-location-storage",
            name = "储物间",
            isBuiltIn = false,
            parentId = DEFAULT_ROOT_LOCATION_ID,
            sortOrder = 4,
        ),
        Location(
            id = "builtin-location-balcony",
            name = "阳台",
            isBuiltIn = false,
            parentId = DEFAULT_ROOT_LOCATION_ID,
            sortOrder = 5,
        ),
    )
}
