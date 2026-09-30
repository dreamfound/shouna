package com.dream.shouna.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 迁移链（ARCHITECTURE-P1 §3.2 起）：`1 → 2`（`location.path`）、`2 → 3`（`item.photo_path`）。
 *
 * 两条迁移都遵守同一条底线：**不启用** `fallbackToDestructiveMigration` —— 那一句会让所有
 * 升级用户数据全丢，与「数据仅本机、卸载即永久丢失」的 FR-41 正面冲突（P1 §8.1-1）。
 *
 * 挂载点：[com.dream.shouna.di.DatabaseModule] 的 `Room.databaseBuilder(...).addMigrations(...)`。
 * 新建库不走本链：全新安装由 [SeedCallback] 直接按同一形态写入（P1 §3.2 注）。
 *
 * 被调用方：`DatabaseModule.provideShounaDatabase`
 */
object Migrations {

    /**
     * `1 → 2`：加 `location.path` 列 + 回填 + 建前缀索引。三步全在**同一个** `Migration` 内。
     *
     * 关于第三步的索引：`path LIKE :prefix || '%'` 这种**表达式形态** SQLite 不会走索引
     * （前缀无法静态提取），子孙查询要写成区间
     * `path >= :prefix AND path < :prefix || char(0xFFFF)` 才能命中 `index_location_path`。
     * 索引本身仍然值得建：千级位置上全表扫描虽然不慢，但 FR-21 / 28 / 22 都在热路径上。
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ① 加列。NOT NULL 必须带 DEFAULT —— SQLite 的 ALTER TABLE ADD COLUMN 限制，
            //    且存量行会先落成空串，由第 ② 步统一回填。
            db.execSQL("ALTER TABLE location ADD COLUMN path TEXT NOT NULL DEFAULT ''")

            // ② 递归 CTE 回填：根 = '/' || id || '/'，其余 = 父.path || id || '/'。
            //
            // **必须包一层 `COALESCE(..., '')`**：递归 CTE 到不了的节点（脏数据成环 / 父级缺失）
            // 在 `tree` 里查不到，标量子查询于是返回 **NULL**；而 `path` 是 `NOT NULL`
            // （第 ① 步强制带 DEFAULT），把 NULL 写进去会直接抛
            // `SQLiteConstraintException: NOT NULL constraint failed: location.path` ——
            // **整个迁移失败 = 升级用户开不了 App**，正是「宁可降级也不能失败」取向要避免的。
            // 兜底成空串后，这类节点保留 `''` 由用户自行收拾，迁移照常完成。
            db.execSQL(
                "WITH RECURSIVE tree(id, path) AS (" +
                    " SELECT id, '/' || id || '/' FROM location WHERE parent_id IS NULL" +
                    " UNION ALL" +
                    " SELECT child.id, tree.path || child.id || '/' FROM location child" +
                    " INNER JOIN tree ON child.parent_id = tree.id" +
                    ") UPDATE location SET path = COALESCE(" +
                    "(SELECT tree.path FROM tree WHERE tree.id = location.id), '')",
            )

            // ③ 普通前缀索引。名称必须是 `index_location_path` —— Room 的 schema 校验按名字比对。
            db.execSQL("CREATE INDEX IF NOT EXISTS index_location_path ON location(path)")
        }
    }

    /**
     * `2 → 3`：加 `item.photo_path` 列（FR-16 拍照记录物品）。
     *
     * 与 [MIGRATION_1_2] 的两点不同，都是刻意的：
     * ① **可空、不带 `DEFAULT`** —— 「没拍过照」的真值与「空串」是两回事，用 NULL 表达；
     *    SQLite 的 `ALTER TABLE ADD COLUMN` 只有加 `NOT NULL` 列时才强制要求 `DEFAULT`，
     *    可空列可以直接加，存量行自动落 NULL。
     * ② **只加列，不建索引、不回填、不动任何既有列**。照片列不参与任何查询条件或排序，
     *    建索引只会增加写入成本；也不需要 UPDATE —— NULL 就是正确的初始值。
     *
     * **图片本体不在此列**：文件名指向 `filesDir/photos/` 下的文件（`util/PhotoStore`）。
     * 迁移只搬元数据；换机 / 误卸载时数据库与图片一起消失（「仅存本机」口径）。
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE item ADD COLUMN photo_path TEXT")
        }
    }
}
