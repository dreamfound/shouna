package com.dream.shouna.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.dream.shouna.data.local.dao.CategoryDao
import com.dream.shouna.data.local.dao.ConfigDao
import com.dream.shouna.data.local.dao.ItemDao
import com.dream.shouna.data.local.dao.LocationDao
import com.dream.shouna.data.local.dao.RecentSearchDao
import com.dream.shouna.data.local.entity.AppConfigEntity
import com.dream.shouna.data.local.entity.CategoryEntity
import com.dream.shouna.data.local.entity.ItemEntity
import com.dream.shouna.data.local.entity.LocationEntity
import com.dream.shouna.data.local.entity.RecentSearchEntity

/**
 * 本地数据库（docs/code-map/02-数据与领域.md）：**5 张表**、`version = 3`、导出 schema。
 *
 * 建库即种子：首次创建由 [SeedCallback] 写入默认位置树 6 节点 + 哨兵 + 8 分类 + 超期阈值
 * （`exportSchema = true` → 导出物见 `app/schemas/com.dream.shouna.data.local.ShounaDatabase/`，
 * `1.json` / `2.json` / `3.json` 并存）。
 *
 * **版本历史**：`1` = P0 的 5 表；`2` = P1-01 加 `location.path` 一列 + 1 索引；`3` = FR-16
 * 加 `item.photo_path` 一列（可空、无索引）。**历次迁移都没有新增表**。
 * 迁移体见 [Migrations]（挂载于 `DatabaseModule`）。
 *
 * 不建的表（§3.1）：`placement`（一物一处已裁定取消）、`item_alias`（别名用既有 `item.alias_blob`）、
 * `tag` / `item_tag`（FR-45 已判删）、`recent_location`（由 `location.last_used_at` 替代）、
 * `item_photo`（照片用 `item.photo_path` 单值一列，不做多图相册）。
 */
@Database(
    entities = [
        ItemEntity::class,
        LocationEntity::class,
        CategoryEntity::class,
        RecentSearchEntity::class,
        AppConfigEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ShounaDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao

    abstract fun locationDao(): LocationDao

    abstract fun categoryDao(): CategoryDao

    abstract fun recentSearchDao(): RecentSearchDao

    abstract fun configDao(): ConfigDao

    companion object {
        /** 数据库文件名。 */
        const val NAME: String = "shouna.db"
    }
}
