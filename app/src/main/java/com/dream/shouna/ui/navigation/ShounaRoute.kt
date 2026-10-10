package com.dream.shouna.ui.navigation

import kotlinx.serialization.Serializable

/**
 * 类型安全路由键。F1 的 4 条（Home / QuickAdd / Search / ItemDetail）+ P0-02 新增 1 条
 * （[LocationBrowse]）+ **P1 新增 4 条**（[Stats] / [ItemEdit] / [Settings] / [CategoryManage]）
 * + **V2 新增 2 条**（[VoiceAiIntake] / [PhotoAiIntake]）= **11 条**。纯声明，不含 Composable。
 *
 * P1 的两条边界（P1 §8.1-16 / §8.1-15）：
 * - **统计下钻不新增路由** —— 卡片态 / 明细态是同一个 `Stats` 目的地的页内两态；
 * - 分类管理**有独立页**（`prd/07` §6.1 未给），由 P-SETTINGS 内的入口进入。
 */

@Serializable
data object Home

/**
 * P-ADD（FR-09 / 10 / 11 / 12）。
 *
 * `locationId` 非 null = **从 P-BROWSE 的「＋ 往这里记一件」进来**，录入页位置条预选该位置；
 * null = 首页「＋ 记一件」进来，位置条走「最近使用」预选（F1 既有行为）。
 * —— 这条参数即 `prd/07` §6.2 跳转图里 `BROWSE --> ADD` 那条边的落地。
 */
@Serializable
data class QuickAdd(val locationId: String? = null)

@Serializable
data class Search(val initialQuery: String? = null)

@Serializable
data class ItemDetail(val itemId: String)

/**
 * P-BROWSE（FR-01 / 02 / 03 / 05）。
 * `locationId = null` 表示**根级**（全部位置），非 null 表示进入该位置那一层。
 */
@Serializable
data class LocationBrowse(val locationId: String? = null)

/**
 * P-STATS（FR-33 / 29 / 38）：归纳统计。
 * 无参数 —— 卡片态与明细态由 ViewModel 的 `mode` 决定，不下沉到路由。
 */
@Serializable
data object Stats

/**
 * P-ITEM-EDIT（P1-04：别名 / 数量 / FR-14 落点）。
 * 详情页的「✏」与「⋯更多」折叠区都进这一条。
 */
@Serializable
data class ItemEdit(val itemId: String)

/** P-SETTINGS（FR-47）：阈值、分类管理入口、隐私说明、关于。 */
@Serializable
data object Settings

/** 分类管理页（FR-44）：由 P-SETTINGS 内的入口进入。 */
@Serializable
data object CategoryManage

/**
 * 语音 AI 录入页（`V2-FR-10`）：说一句 → 识别 → 逐条确认弹窗 → 逐条落库。
 *
 * 无参数 —— 本页**没有「先定位置」这一步**：位置由那句话决定（模型返回路径、本地匹配，
 * 解析不出的层降级到最近的已存在祖先，仍不行就由用户在弹窗里补）。
 * 本页目前只有首页「🎤 语音AI记」一个入口。
 *
 * 与 [PhotoAiIntake] 是**同一套结果段的两个输入段**：两页共用 `ui/ai/AiItemQueue` 与
 * `ui/component/AiItemDialog`。
 */
@Serializable
data object VoiceAiIntake

/**
 * 拍照 AI 录入页（`V2-FR-11` / `V2-FR-13`）：先定位置 → 再拍 / 选一张照片 → 识别 → 逐条确认 → 落库。
 *
 * 无参数 —— 位置一律在**页内**先定（进页预选最近使用，`V2-FR-13`「位置先定后用」），不从路由传入；
 * 本页目前只有首页「📷 拍照AI记」一个入口。
 */
@Serializable
data object PhotoAiIntake
