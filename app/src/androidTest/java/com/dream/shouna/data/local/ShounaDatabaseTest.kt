package com.dream.shouna.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dream.shouna.data.local.dao.ConfigDao
import com.dream.shouna.data.local.dao.RecentSearchDao
import com.dream.shouna.data.local.entity.ItemEntity
import com.dream.shouna.data.local.entity.RecentSearchEntity
import com.dream.shouna.data.memory.BuiltInData
import com.dream.shouna.domain.model.ItemStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0-01 的**仪器测试**（ARCHITECTURE-P0 §8.1-13：Room 的验证走 androidTest）：
 * 种子完整性、外键取向（RESTRICT / SET NULL）、计数用的原始 SQL、`recent_search` 的
 * REPLACE 去重与裁剪。
 *
 * 分工：仓库层的**业务逻辑**在 JVM 单测（`data/repository` 下的各 `…Test` + 内存替身 `FakeDaos`）里覆盖；
 * 本类只测**替身替代不了的部分** —— 真实 SQLite 与 Room 生成代码的行为。
 *
 * 每个用例各建一个**内存库**（`inMemoryDatabaseBuilder` + `SeedCallback`）→ 互不干扰。
 * 内存库同样是「首次新建」，故种子回调照常触发，与首启真机的状态等价。
 */
@RunWith(AndroidJUnit4::class)
class ShounaDatabaseTest {

    private lateinit var database: ShounaDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, ShounaDatabase::class.java)
            .addCallback(SeedCallback())
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---- 种子（§3.3） -------------------------------------------------------------

    @Test
    fun seed_writesSentinelTreeCategoriesAndThreshold() = runBlocking {
        val locations = database.locationDao().observeAll().first()

        // 哨兵 1 条 + 默认树 6 节点。
        assertEquals(1 + BuiltInData.DEFAULT_LOCATION_TREE.size, locations.size)
        val sentinel = locations.single { it.isBuiltIn }
        assertEquals(BuiltInData.UNSPECIFIED_LOCATION_ID, sentinel.id)
        assertEquals("未指定位置", sentinel.name)
        // 哨兵为**根级**：否则「不知道在哪」会被伪装成「在家」。
        assertNull(sentinel.parentId)

        // 默认树与用户自建位置同权（isBuiltIn = false，可改名可删除）。
        val tree = locations.filterNot { it.isBuiltIn }
        assertEquals(BuiltInData.DEFAULT_LOCATION_TREE.size, tree.size)
        assertEquals(5, tree.count { it.parentId == BuiltInData.DEFAULT_ROOT_LOCATION_ID })
        assertNull(tree.single { it.id == BuiltInData.DEFAULT_ROOT_LOCATION_ID }.parentId)
        assertEquals("家", tree.single { it.parentId == null }.name)

        assertEquals(BuiltInData.BUILT_IN_CATEGORY_COUNT, database.categoryDao().count())
        assertEquals("6", database.configDao().get(ConfigDao.KEY_THRESHOLD_MONTHS))
        assertEquals(0, database.itemDao().count())
    }

    // ---- 外键取向（§3.4 不变量 1） ------------------------------------------------

    @Test
    fun itemWithUnknownLocation_isRejected() {
        // 孤儿防线：`item.location_id` → `location.id` 是 ON DELETE RESTRICT，插入非法引用同样被拒。
        val error = assertThrows(Exception::class.java) {
            runBlocking { database.itemDao().insert(item(locationId = "no-such-location")) }
        }

        assertTrue("期望外键约束失败，实际：$error", error.isConstraintFailure())
    }

    @Test
    fun locationWithItems_cannotBeDeleted() = runBlocking {
        val locationId = BuiltInData.DEFAULT_ROOT_LOCATION_ID
        database.itemDao().insert(item(locationId = locationId))

        val error = assertThrows(Exception::class.java) {
            runBlocking { database.locationDao().delete(locationId) }
        }

        // 删除位置必须先迁移 / 标记其下物品，DB 层不允许隐式级联删物品。
        assertTrue("期望外键约束失败，实际：$error", error.isConstraintFailure())
        assertEquals(1, database.itemDao().count())
    }

    @Test
    fun deletingCategory_setsItemCategoryToNull() = runBlocking {
        val categoryId = BuiltInData.BUILT_IN_CATEGORIES.first().id
        database.itemDao().insert(
            item(locationId = BuiltInData.DEFAULT_ROOT_LOCATION_ID, categoryId = categoryId),
        )

        // CategoryDao 是只读的（分类管理属 P1）→ 直接下 SQL 触发 `ON DELETE SET NULL`。
        database.openHelper.writableDatabase
            .execSQL("DELETE FROM category WHERE id = ?", arrayOf<Any?>(categoryId))

        assertNull(database.itemDao().observeAll().first().single().categoryId)
    }

    // ---- 原始 SQL（替身测不到的部分） ---------------------------------------------

    @Test
    fun activeCounts_excludeGoneItems() = runBlocking {
        val locationId = BuiltInData.DEFAULT_ROOT_LOCATION_ID
        val itemDao = database.itemDao()
        itemDao.insert(item(locationId = locationId, name = "在存放中"))
        itemDao.insert(item(locationId = locationId, name = "不在了", status = ItemStatus.GONE))

        val counts = itemDao.observeActiveCounts().first()

        // `observeActiveCounts` 的 SQL 里写的是 `status != 'gone'` —— 本断言同时证明
        // 「Converters 落的稳定 code」与 SQL 字面量一致（这是替身无法替代的一环）。
        assertEquals(1, counts.single { it.locationId == locationId }.itemCount)
        assertEquals(2, itemDao.count())
    }

    @Test
    fun recentSearch_replaceDedupesByNormalizedQuery() = runBlocking {
        val dao = database.recentSearchDao()
        dao.upsert(recent(query = "风扇", normalized = "风扇", at = 100L))
        dao.upsert(recent(query = "工具箱", normalized = "工具箱", at = 200L))

        // 归一化同词、原始串不同 → 唯一索引冲突走 REPLACE：只留最近一条（并带上最新的原串）。
        dao.upsert(recent(query = "风扇 ", normalized = "风扇", at = 300L))

        val rows = dao.observeRecent(RecentSearchDao.RECENT_SEARCH_LIMIT).first()
        assertEquals(2, rows.size)
        assertEquals("风扇 ", rows.first().query)
        assertEquals(300L, rows.first().searchedAt)
    }

    @Test
    fun recentSearch_trimKeepsTheNewestRows() = runBlocking {
        val dao = database.recentSearchDao()
        repeat(RecentSearchDao.RECENT_SEARCH_LIMIT + 5) { index ->
            dao.upsert(recent(query = "词-$index", normalized = "词-$index", at = index.toLong()))
        }

        dao.trim(RecentSearchDao.RECENT_SEARCH_LIMIT)

        assertEquals(RecentSearchDao.RECENT_SEARCH_LIMIT, dao.count())
        val rows = dao.observeRecent(RecentSearchDao.RECENT_SEARCH_LIMIT).first()
        assertEquals("词-24", rows.first().query)
        assertTrue(rows.none { it.query == "词-0" })
    }

    // ---- 夹具 ---------------------------------------------------------------------

    private var itemSeq = 0

    private fun item(
        locationId: String,
        name: String = "电风扇",
        categoryId: String? = null,
        status: ItemStatus = ItemStatus.IN_STORAGE,
    ): ItemEntity {
        itemSeq += 1
        return ItemEntity(
            id = "item-$itemSeq",
            name = name,
            normalizedName = name.lowercase(),
            pinyinFull = "dianfengshan",
            pinyinInitial = "dfs",
            aliasBlob = "",
            locationId = locationId,
            categoryId = categoryId,
            status = status,
            quantity = 1,
            note = null,
            createdAt = itemSeq.toLong(),
            lastModifiedAt = itemSeq.toLong(),
            lastConfirmedAt = null,
        )
    }

    private fun recent(query: String, normalized: String, at: Long): RecentSearchEntity =
        RecentSearchEntity(query = query, normalizedQuery = normalized, searchedAt = at)

    /**
     * 是否是**约束失败**（外键 RESTRICT / 唯一索引）。
     * 不锁定具体异常类：Room 换 SQLite 驱动时抛出的类型会变，但 SQLite 的文案始终含 "constraint"。
     */
    private fun Exception.isConstraintFailure(): Boolean =
        message.orEmpty().contains("constraint", ignoreCase = true)
}
