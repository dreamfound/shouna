package com.dream.shouna.data.local

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import javax.inject.Inject

/**
 * 跨表写的「事务边界」抽象（ARCHITECTURE-P0 §2 写路径守卫）。
 *
 * 为什么需要这一层：`RoomDatabase.withTransaction` 需要真实的 Room 实例，而 **JVM 单测里没有
 * Android 运行时** → 仓库层若直接依赖 `ShounaDatabase`，「删除子树不留孤儿」「迁移 / 标记两档」
 * 这类**纯逻辑**就只能在 androidTest 里验证。
 *
 * 抽出本接口后：生产走 Room 事务（保证原子性），JVM 测试用直接执行的实现
 * —— 逻辑仍被完整覆盖，而「事务本身是否生效」交给 Room 自己保证。
 */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

/** 生产实现：Room 的 `withTransaction`。 */
class RoomTransactionRunner @Inject constructor(
    private val database: RoomDatabase,
) : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
}
