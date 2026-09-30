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
 * 本地数据库（ARCHITECTURE-P0 §3 + P1 §3.2）：**5 张表**、`version = 2`、导出 schema。
 *
 * 建库即种子：首次创建由 [SeedCallback] 写入默认位置树 6 节点 + 哨兵 + 8 分类 + 超期阈值
 * （`exportSchema = true` → 导出物见 `app/schemas/com.dream.shouna.data.local.ShounaDatabase/`，
 * `1.json` 与 `2.json` 并存）。
 *
 * **版本历史**：`1` = P0 的 5 表；`2` = P1-01 加 `location.path` 一列 + 1 索引
 * （迁移体见 [Migrations.MIGRATION_1_2]，挂载于 `DatabaseModule`）。本版**未新增表**。
 *
 * 不建的表（§3.1）：`placement`（一物一处已裁定取消）、`item_alias`（别名用既有 `item.alias_blob`）、
 * `tag` / `item_tag`（P2）、`recent_location`（由 `location.last_used_at` 替代）。
 */
@Database(
    entities = [
        ItemEntity::class,
        LocationEntity::class,
        CategoryEntity::class,
        RecentSearchEntity::class,
        AppConfigEntity::class,
    ],
    version = 2,
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
