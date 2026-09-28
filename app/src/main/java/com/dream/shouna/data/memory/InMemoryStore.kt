package com.dream.shouna.data.memory

import com.dream.shouna.domain.model.StoredItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * F1 唯一可变状态 owner（ARCHITECTURE §2）：`MutableStateFlow<List<StoredItem>>`，
 * 写入以 [Mutex] 串行化，**不落盘、重启即丢**；对外不暴露可变引用。
 *
 * 被调用方：`ItemRepositoryImpl`（§2 明令：`data/memory` 不得被 UI 直接依赖）
 */
@Singleton
class InMemoryStore @Inject constructor() {
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<StoredItem>>(emptyList())

    /** 只读快照流。 */
    val items: StateFlow<List<StoredItem>> = state.asStateFlow()

    /** 在 [Mutex] 临界区内原子追加（F1-03 单测：连续录入 30 次不丢条目）。 */
    suspend fun append(item: StoredItem): StoredItem {
        // 整体替换为「旧列表 + 新条目」，读改写全程在同一临界区内 → 并发追加不丢条目。
        mutex.withLock {
            state.value = state.value + item
        }
        return item
    }

    /** 在 [Mutex] 临界区内按 id 变换，未命中返回 null。 */
    suspend fun update(id: String, transform: (StoredItem) -> StoredItem): StoredItem? {
        var updated: StoredItem? = null
        mutex.withLock {
            state.value = state.value.map { current ->
                if (current.id == id) transform(current).also { updated = it } else current
            }
        }
        // 未命中：列表原样回写，返回 null（不抛异常，由调用方决定口径）。
        return updated
    }

    suspend fun findById(id: String): StoredItem? = items.value.firstOrNull { it.id == id }

    suspend fun snapshot(): List<StoredItem> = items.value
}
