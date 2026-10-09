package com.dream.shouna.domain.ai

/**
 * 确认卡的一行 —— **已经匹配过的落点**，由 `AiIntake` 组装（`实现约束.md` §2-2：展示类派生一律
 * 在 VM / 用例层，Composable 只画不判）。
 *
 * 与 [AiDraft] 的差别：草稿是模型的原话（路径是**分段数组**），本类型是算好的结果 ——
 * 「命中哪一层 / 哪几层要新建 / 分类对应哪个 id」都在这里定好，组件只负责画与回调。
 *
 * 【为什么放在 domain、而不是某个页面的包里】它有**两个调用方**：连续录入页（P-ADD）与首页
 * （P-HOME）各出一张确认卡。放在任一页面的包里，另一页就得反向依赖那一页。
 *
 * [selected] 支撑 `V2-FR-11` 的「默认全选，可逐行取消」。
 */
data class AiDraftRow(
    val name: String,
    /** 展示用位置文本（命中时=树里的权威路径；未命中时=模型给的分段串）。 */
    val locationText: String? = null,
    /** 命中的最深层位置 id（全未命中 = null）。 */
    val locationId: String? = null,
    /** 未命中的层名，按父链顺序 —— 点确认后才逐层建。 */
    val pendingLocationNames: List<String> = emptyList(),
    val categoryName: String? = null,
    val categoryId: String? = null,
    val note: String? = null,
    val selected: Boolean = true,
) {
    /** 卡片上标「待新建」用；**只是标记**，确认前库中零新增（§3.3 硬边界 2）。 */
    val locationPendingCreation: Boolean get() = pendingLocationNames.isNotEmpty()

    /**
     * 这一行**没有任何位置线索**（连待新建的层名都没有）—— 落库时会因「位置必填」被跳过。
     *
     * 它只在**首页**那条通道上真正出现：录入页有「已选位置」兜底，而首页没有这个兜底值，
     * 一句话里若某件物品完全没提位置，就必须在卡片上**明说**它不会被记下（见 `AiIntake.commitRows`）。
     */
    val hasNoLocationHint: Boolean
        get() = !locationPendingCreation && locationId == null
}
