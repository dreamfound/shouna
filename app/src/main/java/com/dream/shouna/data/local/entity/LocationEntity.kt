package com.dream.shouna.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `location` 表（ARCHITECTURE-P0 §3.1 + P1 §3.2）：位置树，`parent_id` 自引用、任意层级。
 *
 * - **`path` 已物化**（P1-01 的首个真实迁移）：ID 序列，形如 `/根id/…/自身id/`，含自身、前后带 `/`。
 *   它是**子树范围查询**（FR-21 计数 / FR-28 批量确认 / FR-22 位置筛选）的索引基础；
 *   名称路径（面包屑）**仍由父链实时派生**（[com.dream.shouna.util.LocationPath]），不入库
 *   —— 名称会改名、位置会移动，物化名称路径必然脏（§8.1-2）。
 * - `parent_id` **ON DELETE RESTRICT**：删位置以子树为单位、自底向上，禁止隐式级联（§3.4 不变量 3）。
 * - `is_built_in = 1` 的位置**不出现在位置选择器与浏览页**：它只为「删位置档 2 的 `gone` 物品收容」
 *   与极端兜底而存在（位置必填口径下，正常录入路径永不落内置位置）。
 * - `is_temporary` 由 P1-02 的 FR-06 写入（标记 / 取消标记）。
 *
 * 不变量（P1 §3.4-7）：任一行 `path` 的最后一段必为自身 `id`，倒数第二段必为其 `parent_id`（根除外）；
 * 移动 / 合并后必须**同事务**重写受影响子树的 `path`。
 */
@Entity(
    tableName = "location",
    foreignKeys = [
        ForeignKey(
            entity = LocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["parent_id"]),
        // P1-01：前缀索引。子孙查询用**区间**形态（`path >= :prefix AND path < :prefix || char(0xFFFF)`）
        // 才能命中它；`LIKE :prefix || '%'` 这种表达式形态 SQLite 不走索引。
        Index(value = ["path"]),
    ],
)
data class LocationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    /** 位置称呼（储物间、纸箱-07）。 */
    @ColumnInfo(name = "name")
    val name: String,
    /** 父级；根级 = NULL。 */
    @ColumnInfo(name = "parent_id")
    val parentId: String?,
    /**
     * P1-01：ID 序列路径，含自身、前后带 `/`（如 `/home/storage/box/`）。
     * 由 [com.dream.shouna.util.LocationPath.buildIdPath] 拼装 —— 迁移回填、建库种子与新建节点
     * 走的是**同一个口径**（P1 §3.2：两条路径必须产出同一形态）。
     *
     * `defaultValue = "''"` **必须与 `Migrations.MIGRATION_1_2` 的 `DEFAULT ''` 逐字一致**：
     * SQLite 的 `ALTER TABLE ... ADD COLUMN NOT NULL` 语法上就得带默认值，因此升级来的库
     * 一定带着 `DEFAULT ''`；实体侧不声明同一个默认值 → Room 的迁移校验会报
     * 「Migration didn't properly handle: location」。两处必须同改。
     */
    @ColumnInfo(name = "path", defaultValue = "''")
    val path: String,
    /** 内置标记；内置位置不在选择器／浏览页露出。 */
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean,
    /** FR-06：临时位置标记；其下物品在列表与统计中带显著标记。 */
    @ColumnInfo(name = "is_temporary")
    val isTemporary: Boolean,
    /** FR-03 备注，可空。 */
    @ColumnInfo(name = "note")
    val note: String?,
    /** 同级排序。 */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    /** FR-11 / FR-23：最近使用时间，**替代 `recent_location` 表**（§8.1-3）。 */
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long?,
    /** epoch millis UTC。 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
