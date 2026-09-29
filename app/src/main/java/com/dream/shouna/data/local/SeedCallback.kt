package com.dream.shouna.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dream.shouna.data.local.dao.ConfigDao
import com.dream.shouna.data.memory.BuiltInData

/**
 * 建库种子（ARCHITECTURE-P0 §3.3）：用 `RoomDatabase.Callback.onCreate()`，语义天然匹配
 * 「仅全新安装首次」，**因此不需要初始化迁移**。
 *
 * 写入内容：哨兵位置 1 条（系统保留）+ 默认位置树 6 节点 + 内置分类 8 条 + 超期阈值。
 * 全部字面量取自 [BuiltInData] 与 [ConfigDao]，不散落。
 *
 * 用 `execSQL` 而非 DAO：`onCreate` 回调发生在数据库可用之前，此时拿不到 DAO 实例。
 */
class SeedCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        val now = System.currentTimeMillis()
        seedLocations(db, now)
        seedCategories(db)
        seedConfig(db)
    }

    private fun seedLocations(db: SupportSQLiteDatabase, now: Long) {
        // 哨兵在前、默认树在后：两者都进 location 表，但口径不同（见 BuiltInData 的 KDoc）。
        val locations = BuiltInData.BUILT_IN_LOCATIONS + BuiltInData.DEFAULT_LOCATION_TREE
        locations.forEach { location ->
            db.execSQL(
                "INSERT INTO location " +
                    "(id, name, parent_id, is_built_in, is_temporary, note, sort_order, last_used_at, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    location.id,
                    location.name,
                    location.parentId,
                    if (location.isBuiltIn) 1 else 0,
                    if (location.isTemporary) 1 else 0,
                    location.note,
                    location.sortOrder,
                    location.lastUsedAt,
                    now,
                ),
            )
        }
    }

    private fun seedCategories(db: SupportSQLiteDatabase) {
        BuiltInData.BUILT_IN_CATEGORIES.forEach { category ->
            db.execSQL(
                "INSERT INTO category (id, name, sort_order, is_built_in) VALUES (?, ?, ?, 1)",
                arrayOf<Any?>(category.id, category.name, category.sortOrder),
            )
        }
    }

    private fun seedConfig(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO app_config (`key`, value) VALUES (?, ?)",
            arrayOf<Any?>(
                ConfigDao.KEY_THRESHOLD_MONTHS,
                ConfigDao.DEFAULT_THRESHOLD_MONTHS.toString(),
            ),
        )
    }
}
