package com.dream.shouna.data.remote.ai

import com.dream.shouna.util.LocationPath

/**
 * AI system prompt 的**分段组装**（**纯函数**，`V2-FR-16`）。
 *
 * 结构 —— 段序**固定且顺序有讲究**，动它之前先读 `docs/code-map/05-改动须知.md` 的提示词口径：
 *
 * | # | 段 | 变不变 | 内容 |
 * |---|---|---|---|
 * | ① | 公共角色段 | 全场景共用 | 角色 +「只输出 JSON」+「不得编造、拿不准填 null」 |
 * | ② | 功能段 | **按 [AiScene] 各一套** | 这一次要干什么（格式化录入 / 拆语音句 / 辨认照片） |
 * | ③ | 输出契约段 | 骨架共用、**逐字段规则按场景** | `{"items":[…]}` 结构 + 四字段定义 |
 * | ④ | 上下文段 | **位置增删时变** | 分类名单 + 位置树（缩进文本） |
 *
 * **为什么稳定段在前、易变段在后**：供应商按**前缀**复用提示词（实测响应 `usage` 里带
 * `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`，即此机制；实测 481 token 的 prompt
 * 有 256 token 命中）。把每次都可能变的位置清单插在中间，会让它**之后**的内容一起失去前缀
 * 复用资格；放在最末，前三段就始终是同一段前缀。
 *
 * **为什么契约段要把结构写死成 `{"items":[…]}`**（2026-10-08 实测结论）：不写死时，模型在
 * 「单个对象」与「items 数组」之间**随机跳**（同一句 prompt、同一张图，6 次里 1 次单对象、5 次数组）
 * —— 而下游 `DraftParser` 只认一种就会静默丢数据。写死后 3/3 稳定。
 *
 * 口径来自 `V2-FR-16`：提示词属**开发侧常量** —— 不进设置页、不进界面、改它不需要改需求文档。
 * 四段共同守住两条硬边界（`docs/code-map/02-数据与领域.md`）：**分类只能从名单里原样选**（AI 绝不新建
 * 分类）、**识别不出一律 null 且不得编造**。
 */
object AiPrompt {

    /**
     * 结构化抽取的采样温度。
     *
     * 取 0：抽取任务要的是「同一句话稳定译成同一份草稿」，不需要发散；也避免同句多译让用户
     * 觉得结果不可预期。数值属开发侧常量（`V2-FR-16`），不进界面。
     */
    const val TEMPERATURE: Double = 0.0

    /**
     * 按场景拼出完整 system prompt。
     *
     * [AiScene.QUERY] 当前**没有功能段文案**（`V2-AI-PRD` §7「本版不引入 AI 查询」）⇒ 该场景下
     * 只拼出①③④段，语义并不完整，**因此也没有任何调用方**（[DeepSeekAiClient] 另有一道场景守卫）。
     * §7 的三个问句有答案后，只需在 [sceneSection] 里补一条分支。
     */
    fun system(
        scene: AiScene,
        categoryNames: List<String>,
        locationPaths: List<String>,
    ): String = buildString {
        append(ROLE)
        sceneSection(scene)?.let(::append)
        append(schemaSection(scene))
        append(contextSection(categoryNames, sceneLocationPaths(scene, locationPaths)))
    }

    /**
     * 位置清单**按场景**决定要不要附：照片通道**不附**（`V2-FR-13`「位置先定后用」—— 位置在拍照
     * 之前就定了，模型既不需要、也不该看到位置清单）。
     *
     * 这条**不由调用方传空列表来保证**，而是在这里兜底：它同时是**隐私口径**的一道底线
     * （`V2-AI-PRD` §5 的出设备白名单），不该依赖每个调用方都记得。
     */
    private fun sceneLocationPaths(scene: AiScene, locationPaths: List<String>): List<String> =
        if (scene == AiScene.PHOTO_INPUT) emptyList() else locationPaths

    // --- ① 公共角色段（全场景共用） ----------------------------------------------

    private const val ROLE =
        "你是一个物品收纳软件的录入助手。\n" +
            "只输出一个 JSON 对象：不要解释、不要前后缀、不要 markdown 代码块标记。\n" +
            "不得编造用户没有说过的信息；拿不准的字段一律填 null。\n"

    // --- ② 功能段（按场景各一套） ------------------------------------------------

    /** 取该场景的功能段；`null` = 本场景暂无文案（见 [system] 的说明）。 */
    private fun sceneSection(scene: AiScene): String? = when (scene) {
        AiScene.TEXT_INPUT -> SCENE_TEXT_INPUT
        AiScene.VOICE_INPUT -> SCENE_VOICE_INPUT
        // 【2026-10-10 停用 · 注释保留】录音场景的功能段：`AiScene.VOICE_AUDIO -> SCENE_VOICE_AUDIO`
        AiScene.PHOTO_INPUT -> SCENE_PHOTO_INPUT
        AiScene.QUERY -> null
    }

    /**
     * 录入 · 文字通道（录入页 P-ADD 的名称框，`V2-FR-02`）。
     * 措辞按用户 2026-10-08 的原话：「只补充确定性，不进行猜测」。
     *
     * 【与语音段的分野】这一路有表单兜底（用户已选位置），位置不是模型的注意点；语音段
     * （[SCENE_VOICE_INPUT]）没有兜底。两者因此各写一套（2026-10-09 用户口径，此前共用）。
     */
    private const val SCENE_TEXT_INPUT =
        "用户正在进入物品收纳信息输入。请格式化用户的输入，并对物品的分类进行补充 ——\n" +
            "只补充你**确定**的，不进行猜测。\n"

    /**
     * 录入 · 语音句通道（语音 AI 录入页 P-VOICE-AI，`V2-FR-10`）。
     *
     * 【为什么要跟文字通道分开】这一路的输入是**语音转文字的一整句话**，可能一口气交代好几件
     * 东西；而且它**没有「已选位置」兜底** —— 位置只能从这句话里来。文字通道有表单兜底，
     * 两者的注意点不同，故各写一套（2026-10-09 用户口径）。
     *
     * 【这一段说了什么】①「这是一段语音转文字」（用户 2026-10-09 的原话，让模型按口语输入来读）；
     * ②「可能同时提到不止一件东西」⇒ 拆成逐条；③ 沿用文字段那句「只补充你确定的」。
     *
     * 【位置规则不在这一段】「逐字照抄清单里的层名」这条指令与位置清单本身都在 ④ 上下文段
     * （[contextSection]）—— 文字 / 语音两个场景共用那一套，此处不重复。
     */
    private const val SCENE_VOICE_INPUT =
        "用户说了一句话来登记物品。这是一段语音转文字，可能同时提到不止一件东西。\n" +
            "请把这句话拆成逐条记录，并对物品的分类进行补充 ——\n" +
            "只补充你**确定**的，不进行猜测。\n"

    // 【2026-10-10 停用 · 注释保留】录音场景的功能段（`AiScene.VOICE_AUDIO`）—— 平台不受理
    // 音频输入，随整条录音链路一并注释。恢复时去掉下面各行行首的 `// ` 即可：
    //
    // /**
    //  * 录入 · 语音**录音**通道（语音 AI 录入页 P-VOICE-AI，`V2-FR-10`；2026-10-10 新增）。
    //  *
    //  * 【为什么要跟 [SCENE_VOICE_INPUT] 分开】两路的输入根本不是一回事：那一路拿到的是**已经写好的
    //  * 句子**，这一路拿到的是**一段录音**（由模型自己听）。措辞必须点明「这是一段录音」——
    //  * 否则模型会等一句「文字输入」，对着音频文件不知所云。位置口径两路相同（没有「已选位置」
    //  * 兜底、只能从听到的内容里来、本地不新建）。
    //  *
    //  * 【这一段说了什么】①「这是一段用户说话的录音」（形态声明，用户 2026-10-10 口径）；
    //  * ②「可能同时提到不止一件东西」⇒ 拆成逐条；③ 沿用文字段那句「只补充你确定的」。
    //  *
    //  * 【位置规则不在这一段】「逐字照抄清单里的层名」这条指令与位置清单本身都在 ④ 上下文段
    //  * （[contextSection]）—— 文字 / 语音（两种形态）三个场景共用那一套，此处不重复。
    //  */
    // private const val SCENE_VOICE_AUDIO =
    //     "用户录了一段话来登记物品。这是一段用户说话的录音，可能同时提到不止一件东西。\n" +
    //         "请把这段录音拆成逐条记录，并对物品的分类进行补充 ——\n" +
    //         "只补充你**确定**的，不进行猜测。\n"

    /**
     * 录入 · 照片通道（`V2-FR-11`）。**不含位置**：`V2-FR-13` 定「位置先定后用」，
     * 模型的返回也不含位置值（由契约段强制 `location_path = null`）。
     *
     * 【为什么现在能写「逐件」了】多份草稿的契约（`{"items":[…]}`）本轮才落地；此前契约段是
     * 单份草稿，写「逐件一行」会与之直接冲突，故当时刻意留白。`V2-FR-09` 说的「多物品拆解」
     * 就此以 `V2-FR-11` 的形态实现 —— 一份草稿列表，逐行确认。
     */
    private const val SCENE_PHOTO_INPUT =
        "用户拍了一张照片来登记物品。请逐件辨认照片里可以辨认的物品，各给一条记录，并补全你确定的分类；\n" +
            "无法辨认或不确定的物品不要输出。位置由用户事先指定，不要输出 location_path。\n"

    // --- ③ 输出契约段（骨架共用；location_path 规则按场景） -------------------------

    /**
     * 契约段 = 结构骨架 + 逐字段规则。**结构骨架对全部场景一致**（这是上一轮的实测结论：
     * 不写死结构，模型会在对象与数组间随机跳）。
     */
    private fun schemaSection(scene: AiScene): String =
        STRUCTURE + NAME_RULE + locationRule(scene) + CATEGORY_RULE + NOTE_RULE

    private const val STRUCTURE =
        "输出结构（必须严格遵守）：\n" +
            "- 永远输出一个 JSON 对象，形如 {\"items\": [ 物品, 物品, ... ]}。\n" +
            "- 只识别出 1 件时，items 仍是长度 1 的数组；一件都没识别出时输出 {\"items\": []}。\n" +
            "物品的字段与规则：\n"

    private const val NAME_RULE =
        "- name：物品名称。识别不出时为 null。\n"

    /**
     * `location_path` 的规则**按场景分岔**：
     * - 文字 / 语音通道：模型负责分段（中文层级切分符不固定，「客厅的抽屉」/「客厅-抽屉」/
     *   「客厅抽屉」都可能），本地只做逐层匹配。
     * - 照片通道：`V2-FR-13` 定「位置先定后用」⇒ 一律 null。**写成硬规则而不是「不提」** ——
     *   不提的话模型仍会自己猜一个位置出来（它见过位置清单的格式），而猜出来的位置会污染草稿。
     */
    private fun locationRule(scene: AiScene): String = when (scene) {
        AiScene.PHOTO_INPUT ->
            "- location_path：一律填 null（位置由用户事先指定，不要由你判断）。\n"

        AiScene.TEXT_INPUT, AiScene.VOICE_INPUT, AiScene.QUERY ->
            "- location_path：存放位置，**从根到叶逐层分段的字符串数组**" +
                "（例：[\"家\",\"客厅\",\"抽屉\"]）；只有一层时也必须是数组；识别不出时为 null。\n"
    }

    private const val CATEGORY_RULE =
        "- category：只能从下方给出的候选里**原样**选一个，没有合适的一律为 null，" +
            "**严禁新造**；未给出候选名单时固定为 null。\n"

    private const val NOTE_RULE =
        "- note：备注（颜色、规格、数量描述等），没有时为 null。\n"

    // --- ④ 上下文段（每次都可能变） ----------------------------------------------

    /**
     * 分类名单与位置树。两段**都可整体缺席** —— 名单为空就整段不输出，而不是输出「（无）」：
     * 后者会让模型以为存在一个叫「无」的分类 / 位置。
     *
     * 位置部分渲染成**缩进树**（见 [renderLocationTree]），而不是「一行一条完整路径」——
     * 后者在清单缺中间层时无法表达父链（用户 2026-10-08 指出：那样无法准确描述子位置层级）。
     * 仍只含**层名文本**（`V2-FR-12`：不含物品、不含内部 id），只是改用缩进而非分隔符表达层级。
     */
    private fun contextSection(categoryNames: List<String>, locationPaths: List<String>): String =
        buildString {
            if (categoryNames.isNotEmpty()) {
                append("现有分类（category 只能从这里原样选）：")
                append(categoryNames.joinToString("、"))
                append('\n')
            }
            val tree = renderLocationTree(locationPaths)
            if (tree.isNotEmpty()) {
                append("已有位置（用缩进表示层级：缩进越深、层级越深；每行是一个位置节点的名称）：\n")
                tree.forEach {
                    append(it)
                    append('\n')
                }
                append("若物品的位置命中上面某个节点，请把从根到该节点沿途的每一行层名依次写进数组、逐字照抄；")
                append("确实没有合适的，才按用户原话分段。\n")
            }
        }

    // --- 位置清单 → 缩进树 ---------------------------------------------------------

    /**
     * 把位置路径清单渲染成**缩进树文本**：层级显式，且**中间节点自动补全**。
     *
     * 为什么不用「一行一条完整路径」（`V2-AI-PRD` 术语表的原定义）：全路径的层级只靠**前缀**
     * 隐含 —— 一旦调用方给的清单里缺了中间层（例如只给「家 › 卧室 › 衣柜」而没给「家 › 卧室」），
     * 模型就判断不出「衣柜」挂在谁下面；`客厅 › 抽屉` 与 `储物间 › 抽屉` 这类同名子位置也无从区分。
     *
     * **为什么能自动补全**：本函数按每条路径的**全部前缀**建树，因此父链的完整性是**渲染的职责**，
     * 不是调用方的 —— 调用方只给叶子路径，也能渲染出完整的层级。
     *
     * 同层顺序 = 调用方给出的顺序（`LinkedHashMap` 保序），树序因此与清单顺序一致。
     */
    private fun renderLocationTree(locationPaths: List<String>): List<String> {
        val roots = LinkedHashMap<String, TreeNode>()
        locationPaths.forEach { raw ->
            val layers = raw.split(PATH_SEPARATOR)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            var siblings = roots
            layers.forEach { name ->
                siblings = siblings.getOrPut(name) { TreeNode(name) }.children
            }
        }
        if (roots.isEmpty()) return emptyList()

        val lines = ArrayList<String>()
        fun walk(siblings: Map<String, TreeNode>, depth: Int) {
            siblings.values.forEach { node ->
                lines += INDENT.repeat(depth) + node.name
                walk(node.children, depth + 1)
            }
        }
        walk(roots, 0)
        return lines
    }

    /** 建树用的临时节点；只活在 [renderLocationTree] 里，不进任何接口。 */
    private class TreeNode(val name: String) {
        val children = LinkedHashMap<String, TreeNode>()
    }

    /** 每层缩进的空格数。1 太空、4 太深；2 在 3~4 层深度下仍一眼可辨。 */
    private const val INDENT = "  "

    /**
     * 拆层的分隔符 —— 与面包屑同源（`LocationPath.SEPARATOR`），前后空白一并吞掉，
     * 免得提示词里说「›」而界面画「/」，或者调用方漏了空格就拆不开。
     */
    private val PATH_SEPARATOR: Regex =
        Regex("\\s*${Regex.escape(LocationPath.SEPARATOR.trim())}\\s*")
}
