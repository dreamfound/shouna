package com.dream.shouna.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dream.shouna.data.local.entity.LocationEntity
import com.dream.shouna.data.memory.BuiltInData
import com.dream.shouna.util.LocationPath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 迁移链的**仪器测试**：`1 → 2`（`location.path`，P1-01）、`2 → 3`（`item.photo_path`，FR-16），
 * 以及两者串起来的 `1 → 3`。
 *
 * 分工（同 P0 的 `ShounaDatabaseTest`）：仓库层业务逻辑在 JVM 单测里覆盖；本类只测
 * **替身替代不了的部分** —— 真实 SQLite 上跑 `ALTER TABLE` + 递归 CTE 回填 + 建索引，
 * 并通过 Room 的 schema 校验比对 `1.json` / `2.json` / `3.json`。
 *
 * 覆盖的验收：
 * 1. 存量库升级后 `location.path` 与 `parent_id` 链一致、无空值；
 * 2. 升级过程不丢数据（五张表的行与字段原样）；
 * 3. **新建库与升级库产出同一形态的 `path`**（§3.2 注：两条路径必须一致）；
 * 4. `item.photo_path` 加列后存量行为 **NULL**、且不产生多余索引；
 * 5. **两级迁移能接上**（v1 用户连跑 `1 → 2` + `2 → 3`）。
 *
 * 另补一条**降级取向**：递归 CTE 到不了的脏数据（成环）保留空串，**不得让升级失败**
 * —— 迁移宁可让该行降级，也不能因为一行异常数据让用户开不了 App。
 *
 * 前置：`app/build.gradle.kts` 已把导出目录 `app/schemas` 挂进 androidTest 资产，
 * [MigrationTestHelper] 据此按「数据库类全名 / `<version>.json`」读取各版本结构。
 */
@RunWith(AndroidJUnit4::class)
class MigrationsTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ShounaDatabase::class.java,
    )

    // ---- ① 回填正确 + ② 不丢数据 ------------------------------------------------

    @Test
    fun migration1To2_backfillsPathFromParentChain_andKeepsEveryRow() {
        val v1 = helper.createDatabase(TEST_DB, 1)
        // 三层小树：家 → 卧室 → 纸箱-07。
        v1.insertLocation(id = "loc-home", parentId = null, name = "家")
        v1.insertLocation(id = "loc-bed", parentId = "loc-home", name = "卧室")
        v1.insertLocation(id = "loc-box", parentId = "loc-bed", name = "纸箱-07")
        v1.insertItem(id = "item-1", locationId = "loc-box", name = "电风扇")
        v1.execSQL("INSERT INTO category (id, name, sort_order, is_built_in) VALUES ('cat-1', '数码', 1, 0)")
        v1.execSQL("INSERT INTO recent_search (query, normalized_query, searched_at) VALUES ('风扇', '风扇', 100)")
        v1.execSQL("INSERT INTO app_config (`key`, `value`) VALUES ('threshold_months', '9')")
        v1.close()

        val v2 = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        // 路径形态：含自身、前后带 `/`，逐段与 parent_id 链一致。
        assertEquals(
            mapOf(
                "loc-home" to "/loc-home/",
                "loc-bed" to "/loc-home/loc-bed/",
                "loc-box" to "/loc-home/loc-bed/loc-box/",
            ),
            v2.locationPaths(),
        )
        v2.locationPaths().forEach { (id, path) ->
            assertTrue("升级后 $id 的 path 不该为空", path.isNotEmpty())
            assertTrue("path 必须以自身 id 收尾，实际 $id → $path", path.endsWith("/$id/"))
        }

        // 五张表的数据原样保留（迁移不丢数据）。
        assertEquals(1, v2.countOf("item"))
        assertEquals(1, v2.countOf("category"))
        assertEquals(1, v2.countOf("recent_search"))
        assertEquals(1, v2.countOf("app_config"))
        assertEquals(3, v2.countOf("location"))
        assertEquals("9", v2.singleString("SELECT value FROM app_config WHERE `key` = 'threshold_months'"))
        assertEquals("电风扇", v2.singleString("SELECT name FROM item WHERE id = 'item-1'"))

        // 第三步的索引确实建出来了（否则 FR-21 / 28 / 22 的子树查询退化成全表扫描）。
        assertEquals(
            1,
            v2.singleInt("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_location_path'"),
        )

        v2.close()
    }

    // ---- ③ 脏数据不该阻断升级 ---------------------------------------------------

    @Test
    fun migration1To2_toleratesRowsTheRecursionCannotReach() {
        val v1 = helper.createDatabase(TEST_DB, 1)
        // 互为父级（成环）：自引用外键是满足的，但递归 CTE 从「根」出发永远到不了这两行。
        v1.insertLocation(id = "loc-a", parentId = "loc-b", name = "A")
        v1.insertLocation(id = "loc-b", parentId = "loc-a", name = "B")
        v1.close()

        val v2 = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        // 行保留、path 降级为空串 —— 迁移**本身仍然成功**，这两行留给用户自己收拾。
        assertEquals(mapOf("loc-a" to "", "loc-b" to ""), v2.locationPaths())
        v2.close()
    }

    // ---- ④ 新建库与升级库同形态 -------------------------------------------------

    @Test
    fun freshInstall_pathUsesTheSameIdSequenceFormAsMigration() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fresh = Room.inMemoryDatabaseBuilder(context, ShounaDatabase::class.java)
            .addCallback(SeedCallback())
            .allowMainThreadQueries()
            .build()

        val rows = fresh.locationDao().observeAll().first()
        val byId = rows.associateBy { it.id }

        // 期望值从**库里的 parent_id 链**独立推导（而不是去比对 BuiltInData 里那份预先算好的
        // 常量）—— 这样才真正验证「建库种子」与「迁移回填」用的是同一个口径（§3.2 注）。
        rows.forEach { row ->
            val expected = chainOf(row.id, byId).fold("") { acc, id -> LocationPath.buildIdPath(id, acc) }
            assertEquals("位置「${row.name}」的 path 与父链不一致", expected, row.path)
            assertTrue("位置「${row.name}」的 path 不该为空", row.path.isNotEmpty())
        }

        // 哨兵 1 条 + 默认树 6 节点，全部有 path（含哨兵 —— 它也是根级、同一形态）。
        assertEquals(1 + BuiltInData.DEFAULT_LOCATION_TREE.size, rows.size)
        assertEquals(1, rows.count { it.id == BuiltInData.UNSPECIFIED_LOCATION_ID })

        fresh.close()
    }

    // ---- ⑤ 2 → 3：加 `item.photo_path`（FR-16） ---------------------------------

    /**
     * 加一列可空列的正确形态：存量行落 **NULL**（不是空串）、不高攀出索引。
     * 「没拍过照」在语义上必须与「照片名为空串」区分开。
     */
    @Test
    fun migration2To3_addsNullablePhotoColumn_andKeepsEveryRow() {
        val v2 = helper.createDatabase(TEST_DB, 2)
        v2.insertLocation(id = "loc-home", parentId = null, name = "家")
        v2.insertItem(id = "item-1", locationId = "loc-home", name = "电风扇")
        v2.close()

        val v3 = helper.runMigrationsAndValidate(TEST_DB, 3, true, Migrations.MIGRATION_2_3)

        assertEquals(1, v3.countOf("item"))
        assertEquals(
            "存量行的 photo_path 必须是 NULL（不是空串）",
            null,
            v3.singleString("SELECT photo_path FROM item WHERE id = 'item-1'"),
        )
        // 只加列、不建索引：照片不参与任何查询条件或排序。
        assertEquals(
            0,
            v3.singleInt(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_item_photo_path'",
            ),
        )
        // 既有列原样（迁移只碰 item 表的一个新列）。
        assertEquals("电风扇", v3.singleString("SELECT name FROM item WHERE id = 'item-1'"))

        v3.close()
    }

    // ---- ⑥ 1 → 3：连跑两级（老用户升级路径） -------------------------------------

    /**
     * `DatabaseModule` 同时挂了 `MIGRATION_1_2` 与 `MIGRATION_2_3`，所以 v1 用户升级会**连跑两条**。
     * 这条用例守的就是「链能不能接上」：两级跑完后 `location.path` 与 `item.photo_path` 同时就位，
     * 且 v1 时期的数据一行不丢。
     */
    @Test
    fun migration1To3_runsBothStepsInOrder_andKeepsData() {
        val v1 = helper.createDatabase(TEST_DB, 1)
        v1.insertLocation(id = "loc-home", parentId = null, name = "家")
        v1.insertLocation(id = "loc-box", parentId = "loc-home", name = "纸箱-07")
        v1.insertItem(id = "item-1", locationId = "loc-box", name = "电风扇")
        v1.close()

        val v3 = helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            Migrations.MIGRATION_1_2,
            Migrations.MIGRATION_2_3,
        )

        // ① 的产物：path 已物化。
        assertEquals(
            mapOf("loc-home" to "/loc-home/", "loc-box" to "/loc-home/loc-box/"),
            v3.locationPaths(),
        )
        // ② 的产物：photo_path 就位且为 NULL。
        assertEquals(null, v3.singleString("SELECT photo_path FROM item WHERE id = 'item-1'"))
        // 数据无损。
        assertEquals("电风扇", v3.singleString("SELECT name FROM item WHERE id = 'item-1'"))

        v3.close()
    }

    // ---- 夹具 ---------------------------------------------------------------------

    private fun SupportSQLiteDatabase.insertLocation(id: String, parentId: String?, name: String) {
        execSQL(
            "INSERT INTO location (id, name, parent_id, is_built_in, is_temporary, note, sort_order," +
                " last_used_at, created_at) VALUES (?, ?, ?, 0, 0, NULL, 0, NULL, 1)",
            arrayOf<Any?>(id, name, parentId),
        )
    }

    private fun SupportSQLiteDatabase.insertItem(id: String, locationId: String, name: String) {
        execSQL(
            "INSERT INTO item (id, name, normalized_name, pinyin_full, pinyin_initial, alias_blob," +
                " location_id, category_id, status, quantity, note, created_at, last_modified_at," +
                " last_confirmed_at) VALUES (?, ?, ?, ?, ?, '', ?, NULL, 'in_storage', 1, NULL, 1, 1, NULL)",
            arrayOf<Any?>(id, name, name.lowercase(), "dianfengshan", "dfs", locationId),
        )
    }

    /** 从根到 [id] 的 id 序列（含自身）。带 visited 兜底，成环时截断而不是死循环。 */
    private fun chainOf(id: String, byId: Map<String, LocationEntity>): List<String> {
        val chain = ArrayDeque<String>()
        val visited = HashSet<String>()
        var cursor: String? = id
        while (cursor != null && visited.add(cursor)) {
            chain.addFirst(cursor)
            cursor = byId[cursor]?.parentId
        }
        return chain.toList()
    }

    private fun SupportSQLiteDatabase.locationPaths(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        query("SELECT id, path FROM location ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(0)] = cursor.getString(1)
            }
        }
        return result
    }

    private fun SupportSQLiteDatabase.countOf(table: String): Int =
        singleInt("SELECT COUNT(*) FROM $table")

    private fun SupportSQLiteDatabase.singleInt(sql: String): Int =
        query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.singleString(sql: String): String? =
        query(sql).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
