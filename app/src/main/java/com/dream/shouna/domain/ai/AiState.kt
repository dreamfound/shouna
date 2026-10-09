package com.dream.shouna.domain.ai

/**
 * AI 入口的五态（`docs/code-map/02-数据与领域.md` AI-02 子步骤①）。
 *
 * 【为什么放在 domain 而不留在某个页面里】连续录入页与首页各自持有一份本状态机的实例，
 * 但**取值与含义必须一致** —— 放在任一页面的包里，另一页只能反向依赖那一页。
 *
 * `Hidden` 与 `Idle` 是两件事，不能并成一个「没有草稿」：前者是**开关没开**（入口都不该出现），
 * 后者是**开着的待命态**（入口在、没有卡片）。合成一个会让「开关刚打开」与「关了再打开」分不清，
 * 入口的进度态也就没有落脚点。
 */
sealed interface AiState {
    /** 总开关未开：无入口、无请求、无卡片。 */
    data object Hidden : AiState

    /** 开关已开、等待用户触发。 */
    data object Idle : AiState

    /** 请求在途：入口转进度态，**不锁定其他输入**（用户可以继续打字）。 */
    data object Parsing : AiState

    /** 拿到草稿，页面渲染确认卡。`rows` 至少 1 行（空结果不会走到这里）。 */
    data class Draft(val rows: List<AiDraftRow>) : AiState

    /**
     * 一行行内提示（**不弹窗**，`实现约束.md` §4-1）。文案按 [AiFailReason] 映射，
     * 由页面持资源（`ui/component/AiFailText.kt`），VM 与用例层不持有中文文案。
     */
    data class Failed(val reason: AiFailReason) : AiState
}

/** AI 入口的失败 / 无可填原因。页面对每种映射一句文案，用户据此知道下一步做什么。 */
enum class AiFailReason {
    /** 开关开着但没配密钥 —— 此时「去设置页填」比「解析失败」有用。 */
    NO_KEY,

    /** 照片通道未定位置（`V2-FR-13`「位置先定后用」）：**不调模型**，直接提示。 */
    NEED_LOCATION_FOR_PHOTO,

    /** 既没照片、名称框也空 —— 没有可送模型的内容。 */
    NEED_INPUT,

    /** 照片在，但读不出（文件缺失 / 解码失败）。 */
    PHOTO_READ_FAILED,

    /** 闸门返 null：网络失败 / 非 2xx / 响应不可解析。 */
    PARSE_FAILED,

    /** 调用成功但一件都没识别出（与 [PARSE_FAILED] 不同，`DraftParser` 的契约如此）。 */
    NOTHING_RECOGNIZED,

    /** 点「填入 / 全部记下」时一行都没勾。 */
    NOTHING_SELECTED,
}
