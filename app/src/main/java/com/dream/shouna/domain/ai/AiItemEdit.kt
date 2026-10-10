package com.dream.shouna.domain.ai

/**
 * 逐条确认弹窗里正在确认的**一件**：识别出的原始行 + 用户可改的四项。
 *
 * 【为什么留一份 [source]】位置与「待新建层」的**原始**取值要能回头查（落库走的是
 * `AiDraftRow`）；名称 / 位置 / 分类 / 备注是用户可改的副本。落库前用 `source.copy(...)`
 * 合成最终行 —— 不做「边改边回写 source」，免得把只读因子也一并冲掉。
 *
 * 【为什么放 domain 而不是某个页面的包里】它的两个调用方分属两页：语音 AI 录入页
 * （P-VOICE-AI）与拍照 AI 录入页（P-PHOTO-AI）。与 [AiDraftRow] 同一理由。
 *
 * [categoryId] 可空 = 未分类（`CategoryChips` 支持再点一次取消）。
 * [locationId] 可空 = **这一行还没有位置**（语音通道里「连祖先都没对上」才会出现）——
 * 弹窗据此把位置行标红并禁用「确认」，用户就地补一个位置即可解禁。
 */
data class AiItemEdit(
    /** 识别出的原始行（待新建层 / 被丢弃层等只读因子都在这里）。 */
    val source: AiDraftRow,
    val name: String,
    /** 用户可改的位置（id 与展示文本**成对**写入，不允许只到一半）。 */
    val locationId: String?,
    val locationText: String?,
    val categoryId: String?,
    val note: String,
) {
    /**
     * 位置是**降级**来的：模型说的层库里没有，落到了更浅的已存在祖先。
     *
     * 【为什么还要判 `locationId == source.locationId`】用户一旦在弹窗里手动改过位置，
     * 这一行就不再是「就近落下的」，那句「已就近」的说明必须跟着消失 —— 否则用户改完还看到
     * 「原说的层不存在」，会以为自己没改成功。
     */
    val locationDowngraded: Boolean
        get() = locationId == source.locationId && source.droppedLocationNames.isNotEmpty()

    /** 被丢弃的层名（降级说明文案用）。 */
    val droppedLocationNames: List<String> get() = source.droppedLocationNames

    /** 位置还没定 —— 弹窗里「确认」据此置灰（`实现约束.md` §4-3）。 */
    val hasNoLocation: Boolean get() = locationId == null

    /** 名称是必填项：空名称同样置灰（与录入页同一口径）。 */
    val canConfirm: Boolean get() = name.isNotBlank() && !hasNoLocation

    companion object {
        /** 由草稿行起一个弹窗条目：可改项取识别结果的初值。 */
        fun from(row: AiDraftRow): AiItemEdit = AiItemEdit(
            source = row,
            name = row.name,
            locationId = row.locationId,
            locationText = row.locationText,
            categoryId = row.categoryId,
            note = row.note.orEmpty(),
        )
    }
}
