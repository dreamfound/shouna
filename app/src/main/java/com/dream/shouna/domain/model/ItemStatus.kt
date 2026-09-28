package com.dream.shouna.domain.model

/**
 * 物品三态。稳定字符串 code（非 `enum.name`），F1 只产生 [IN_STORAGE]，
 * 其余两态仅定义、不产生、无切换入口（ARCHITECTURE §3.5）。
 */
enum class ItemStatus(
    val code: String,
    val isActive: Boolean,
) {
    IN_STORAGE("in_storage", true),
    TO_BE_PUT_BACK("to_be_put_back", true),
    GONE("gone", false),
    ;

    companion object {
        /** code ↔ 枚举反查。F1 无调用方（三态只产生 [IN_STORAGE]，无持久化读回）。 */
        fun fromCode(code: String): ItemStatus? = entries.firstOrNull { it.code == code }
    }
}
