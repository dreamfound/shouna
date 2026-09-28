package com.dream.shouna.util

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * id 生成（ARCHITECTURE §3.2）：`IdGenerator.newId()`。
 *
 * 被调用方：`ItemRepositoryImpl.createItemQuick` → `StoredItem.id`
 */
interface IdGenerator {
    fun newId(): String
}

/** 默认实现：UUID。 */
@Singleton
class UuidIdGenerator @Inject constructor() : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}
