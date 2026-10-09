package com.dream.shouna.domain.ai

/**
 * AI 结构化草稿 —— `docs/code-map/02-数据与领域.md` 的**唯一契约**。
 *
 * 四条硬口径：
 * 1. **永不直接落库**：草稿只是待确认的中间产物，写库必须由用户点「保存并继续」触发；
 * 2. 四个字段**均可为 null**（模型识别不出即为 null，不允许编造）；
 * 3. [locationPath] 是**从根到叶的层级数组**——分段由模型承担，本地只做逐层匹配（中文的层级切分符
 *    不固定，「客厅的抽屉」/「客厅-抽屉」/「客厅抽屉」都可能）；
 * 4. [category] 必须**精确命中**现有分类，否则为 null（AI 绝不新建分类）。
 *
 * 纯 Kotlin 模型（守 `实现约束.md` §2-8），不依赖 Android 框架，可在 JVM 单测直跑。
 */
data class AiDraft(
    val name: String? = null,
    val locationPath: List<String>? = null,
    val category: String? = null,
    val note: String? = null,
) {
    /** 四字段全空——调用方据此「不出卡、只给一行提示」（`V2-FR-09`）。 */
    val isEmpty: Boolean
        get() = name.isNullOrBlank() &&
            locationPath.isNullOrEmpty() &&
            category.isNullOrBlank() &&
            note.isNullOrBlank()
}
