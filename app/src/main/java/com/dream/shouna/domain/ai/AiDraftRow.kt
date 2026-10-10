package com.dream.shouna.domain.ai

/**
 * 确认卡的一行 —— **已经匹配过的落点**，由 `AiIntake` 组装（`实现约束.md` §2-2：展示类派生一律
 * 在 VM / 用例层，Composable 只画不判）。
 *
 * 与 [AiDraft] 的差别：草稿是模型的原话（路径是**分段数组**），本类型是算好的结果 ——
 * 「命中哪一层 / 哪几层要新建 / 分类对应哪个 id」都在这里定好，组件只负责画与回调。
 *
 * 【为什么放在 domain、而不是某个页面的包里】它有**三个调用方**：连续录入页（P-ADD）、
 * 语音 AI 录入页（P-VOICE-AI）与拍照 AI 录入页（P-PHOTO-AI）。放在任一页面的包里，
 * 另外两页就得反向依赖那一页。
 *
 * [selected] 支撑 `V2-FR-11` 的「默认全选，可逐行取消」（现由录入页的确认卡使用）。
 */
data class AiDraftRow(
    val name: String,
    /** 展示用位置文本（命中时=树里的权威路径；未命中时=模型给的分段串）。 */
    val locationText: String? = null,
    /** 命中的最深层位置 id（全未命中 = null）。 */
    val locationId: String? = null,
    /** 未命中的层名，按父链顺序 —— 点确认后才逐层建。 */
    val pendingLocationNames: List<String> = emptyList(),
    /**
     * **模型提到、但库里没有、且调用方选择「不新建」**的层名（按父链顺序）。
     *
     * 只有语音通道会非空：它的口径是「落到最深的**已存在**祖先」，解析不出的尾部层一律丢弃、
     * 降级到 [locationId]（`V2-AI-PRD.md` §4 `V2-FR-10` 落点补注）。与 [pendingLocationNames]
     * **语义互斥** —— 前者是「确认后逐层新建」，本字段是「不建，就记到更浅的祖先」。
     *
     * 它的唯一用途是**呈现**：弹窗位置行要把「说的层不存在、已就近」讲给用户听，
     * 否则用户会以为模型认错了位置。用户一旦手动改了位置，这个标记即失效
     * （见 `AiItemEdit.locationDowngraded`）。
     */
    val droppedLocationNames: List<String> = emptyList(),
    val categoryName: String? = null,
    val categoryId: String? = null,
    val note: String? = null,
    val selected: Boolean = true,
) {
    /** 卡片上标「待新建」用；**只是标记**，确认前库中零新增（§3.3 硬边界 2）。 */
    val locationPendingCreation: Boolean get() = pendingLocationNames.isNotEmpty()

    /** 位置是**降级**来的：模型说的层不存在，落到了更浅的已存在祖先（见 [droppedLocationNames]）。 */
    val locationDowngraded: Boolean get() = droppedLocationNames.isNotEmpty()

    /**
     * 这一行**没有任何位置线索**（连待新建的层名都没有）—— 落库时会因「位置必填」被跳过。
     *
     * 它只在**语音通道**上真正出现：连续录入页有「已选位置」兜底，拍照通道的位置是用户先定的
     * （恒非空），只有语音通道「连一个祖先都没对上」时才会落到这里。弹窗据此把位置行标红、
     * 并给用户一个**就地补位置**的机会（补上即可确认）。
     */
    val hasNoLocationHint: Boolean
        get() = !locationPendingCreation && locationId == null
}
