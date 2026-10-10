package com.dream.shouna.data.remote.ai

import com.dream.shouna.util.LocationPath
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 提示词组装单测（纯 JVM，不触网）：四段的**存在性、顺序、按场景的差异**。
 *
 * 提示词是「开发侧常量」（`V2-FR-16`），改它不需要改需求文档 —— 但下面几条口径不该动：
 * 段序（前缀复用）、清单为空时整段缺席（而不是输出「（无）」）、照片场景不附位置（`V2-FR-13`）。
 */
class AiPromptTest {

    private val categories = listOf("电子配件", "厨房用品")
    private val locations = listOf("家 › 客厅 › 抽屉", "家 › 卧室")

    @Test
    fun system_textInput_containsAllFourSectionsInOrder() {
        val prompt = AiPrompt.system(AiScene.TEXT_INPUT, categories, locations)

        val role = prompt.indexOf("物品收纳软件的录入助手")
        val scene = prompt.indexOf("只补充你")
        val schema = prompt.indexOf("输出结构")
        val category = prompt.indexOf("现有分类")
        val location = prompt.indexOf("已有位置")

        assertThat(role).isAtLeast(0)
        assertThat(scene).isGreaterThan(role)
        assertThat(schema).isGreaterThan(scene)
        assertThat(category).isGreaterThan(schema)
        assertThat(location).isGreaterThan(category)
    }

    @Test
    fun system_textInput_statesOnlyCertainCategories() {
        // 用户 2026-10-08 的原话：「只补充确定性，不进行猜测」。
        val prompt = AiPrompt.system(AiScene.TEXT_INPUT, categories, locations)

        assertThat(prompt).contains("只补充你**确定**的，不进行猜测")
    }

    @Test
    fun system_allScenes_keepHardBoundaries() {
        // 两条硬边界与「只输出 JSON」在任何场景下都必须出现（docs/code-map/02-数据与领域.md）。
        AiScene.entries.forEach { scene ->
            val prompt = AiPrompt.system(scene, categories, locations)

            assertThat(prompt).contains("只输出一个 JSON 对象")
            assertThat(prompt).contains("严禁新造")
            assertThat(prompt).contains("不得编造")
        }
    }

    @Test
    fun system_photoInput_differsFromTextInputAndOmitsLocations() {
        val photo = AiPrompt.system(AiScene.PHOTO_INPUT, categories, locations)
        val text = AiPrompt.system(AiScene.TEXT_INPUT, categories, locations)

        assertThat(photo).isNotEqualTo(text)
        assertThat(photo).contains("不要输出 location_path")
        // V2-FR-13：位置先定后用 ⇒ 即便调用方传了清单，照片场景也不得把它发出去。
        assertThat(photo).doesNotContain("已有位置")
        assertThat(photo).doesNotContain(locations.first())
    }

    @Test
    fun system_voiceInput_hasOwnSectionAndKeepsLocations() {
        // 用户 2026-10-09 口径：语音页**不再**复用文字通道的功能段，且有自己的一句
        //「这是一段语音转文字」。
        val voice = AiPrompt.system(AiScene.VOICE_INPUT, categories, locations)
        val text = AiPrompt.system(AiScene.TEXT_INPUT, categories, locations)

        assertThat(voice).isNotEqualTo(text)
        assertThat(voice).contains("这是一段语音转文字")
        assertThat(voice).contains("可能同时提到不止一件东西")
        assertThat(voice).doesNotContain("照片")
        // 位置仍由模型从这句话里给出（本地不新建）：与照片场景的「一律 null」正好相反 ——
        // 位置树按缩进逐层渲染，故断言层名而不是整条路径。
        assertThat(voice).contains("已有位置")
        assertThat(voice).contains("抽屉")
        assertThat(voice).doesNotContain("位置由用户事先指定")
    }

    // 【2026-10-10 停用 · 注释保留】录音场景（`AiScene.VOICE_AUDIO`）的两条用例 —— 平台不受理
    // 音频输入，该场景连同整条录音链路一并注释。恢复时去掉下面各行行首的 `// ` 即可：
    //
    // @Test
    // fun system_voiceAudio_hasOwnSectionAndKeepsLocations() {
    //     // 2026-10-10：录音通道（P-VOICE-AI 换的新输入形态）有自己的功能段，措辞必须点明
    //     // 「这是一段录音」—— 否则模型会等一句「文字输入」，对着音频不知所云。
    //     val audio = AiPrompt.system(AiScene.VOICE_AUDIO, categories, locations)
    //
    //     assertThat(audio).contains("这是一段用户说话的录音")
    //     assertThat(audio).contains("可能同时提到不止一件东西")
    //     assertThat(audio).doesNotContain("语音转文字")
    //     // 位置口径与语音句通道相同（只能从听到的内容里来、本地不新建）：仍附位置清单，
    //     // 与照片场景的「一律 null」正好相反。
    //     assertThat(audio).contains("已有位置")
    //     assertThat(audio).contains("抽屉")
    //     assertThat(audio).doesNotContain("位置由用户事先指定")
    // }
    //
    // @Test
    // fun system_voiceAudio_allowsLocationPathArray() {
    //     // 录音通道的位置规则走「从根到叶数组」那一支（不是照片场景的「一律填 null」）。
    //     val audio = AiPrompt.system(AiScene.VOICE_AUDIO, categories, locations)
    //
    //     assertThat(audio).contains("从根到叶逐层分段的字符串数组")
    //     assertThat(audio).doesNotContain("location_path：一律填 null")
    // }

    @Test
    fun system_queryScene_hasNoSceneSection() {
        // V2-AI-PRD §7：本版不引入 AI 查询 ⇒ 没有功能段文案（等三个问句有答案再补）。
        val query = AiPrompt.system(AiScene.QUERY, categories, locations)

        assertThat(query).doesNotContain("只补充你")
        assertThat(query).doesNotContain("照片")
        assertThat(query).contains("输出结构")
    }

    @Test
    fun system_emptyCategoryNames_omitsThatSection() {
        // 整段缺席，而不是输出「（无）」—— 后者会让模型以为存在一个叫「无」的分类。
        val prompt = AiPrompt.system(AiScene.TEXT_INPUT, emptyList(), locations)

        assertThat(prompt).doesNotContain("现有分类")
        assertThat(prompt).contains("已有位置")
    }

    @Test
    fun system_emptyLocationPaths_omitsThatSection() {
        val prompt = AiPrompt.system(AiScene.TEXT_INPUT, categories, emptyList())

        assertThat(prompt).doesNotContain("已有位置")
        assertThat(prompt).contains("现有分类")
    }

    @Test
    fun system_locationTree_rendersIndentedInsteadOfFlatPaths() {
        val prompt = AiPrompt.system(AiScene.TEXT_INPUT, categories, locations)

        assertThat(prompt).contains("电子配件、厨房用品")
        // 位置改成**缩进树**：层名逐字在，但「全路径 + 分隔符」那种行**不再出现**。
        assertThat(prompt).contains("\n家\n")
        assertThat(prompt).contains("\n  客厅\n")
        assertThat(prompt).contains("\n    抽屉\n")
        assertThat(prompt).contains("\n  卧室\n")
        assertThat(prompt).doesNotContain("家 › 客厅 › 抽屉")
        assertThat(prompt).doesNotContain(LocationPath.SEPARATOR)
    }

    @Test
    fun system_locationTree_indentsByDepth() {
        // 缩进深度 = 层级深度：一层 2 空格。
        val prompt = AiPrompt.system(
            AiScene.TEXT_INPUT,
            categories,
            listOf("家 › 客厅 › 电视柜 › 上层"),
        )

        assertThat(prompt).contains("\n家\n")
        assertThat(prompt).contains("\n  客厅\n")
        assertThat(prompt).contains("\n    电视柜\n")
        assertThat(prompt).contains("\n      上层\n")
    }

    @Test
    fun system_locationTree_completesMissingIntermediateNodes() {
        // 调用方**只给叶子路径**，中间层「卧室」也必须出现在树上 —— 否则父链断裂，
        // 模型无从判断「衣柜」挂在谁下面（这正是改用缩进树要解决的问题）。
        val prompt = AiPrompt.system(
            AiScene.TEXT_INPUT,
            categories,
            listOf("家 › 卧室 › 衣柜"),
        )

        assertThat(prompt).contains("\n家\n")
        assertThat(prompt).contains("\n  卧室\n")
        assertThat(prompt).contains("\n    衣柜\n")
    }

    @Test
    fun system_locationTree_ordersDepthFirst() {
        // 清单乱序给入（父节点甚至排在子节点之后），输出仍是深度优先的树序：
        // 父 → 子 → 下一个兄弟；同一子树的行必相邻。
        val prompt = AiPrompt.system(
            AiScene.TEXT_INPUT,
            categories,
            listOf("家 › 客厅 › 抽屉", "家 › 卧室 › 衣柜", "家 › 客厅"),
        )

        val home = prompt.indexOf("\n家\n")
        val living = prompt.indexOf("\n  客厅\n")
        val drawer = prompt.indexOf("\n    抽屉\n")
        val bedroom = prompt.indexOf("\n  卧室\n")
        val wardrobe = prompt.indexOf("\n    衣柜\n")

        assertThat(living).isGreaterThan(home)
        assertThat(drawer).isGreaterThan(living)
        // 「客厅」整棵子树排在「卧室」之前 —— 这正是扁平清单读不出来的信息。
        assertThat(bedroom).isGreaterThan(drawer)
        assertThat(wardrobe).isGreaterThan(bedroom)
    }

    @Test
    fun system_locationTree_keepsSameNameUnderDifferentParents() {
        // 客厅与卧室下各有一个「抽屉」：缩进树上位置一目了然。
        val prompt = AiPrompt.system(
            AiScene.TEXT_INPUT,
            categories,
            listOf("家 › 客厅 › 抽屉", "家 › 卧室 › 抽屉"),
        )

        assertThat(prompt).contains("\n  客厅\n    抽屉\n")
        assertThat(prompt).contains("\n  卧室\n    抽屉\n")
    }

    @Test
    fun system_allScenes_declareItemsStructure() {
        // 结构骨架对全部场景一致（2026-10-08 实测：不写死结构，模型会在「单对象」与
        // 「items 数组」之间随机跳，而下游只认一种就会静默丢数据）。
        AiScene.entries.forEach { scene ->
            val prompt = AiPrompt.system(scene, categories, locations)

            assertThat(prompt).contains("\"items\"")
            assertThat(prompt).contains("长度 1 的数组")
            assertThat(prompt).contains("{\"items\": []}")
        }
    }

    @Test
    fun system_textInput_allowsLocationPathArray() {
        val prompt = AiPrompt.system(AiScene.TEXT_INPUT, categories, locations)

        assertThat(prompt).contains("从根到叶逐层分段的字符串数组")
        // 断言要**盯住位置规则那一条**，不能只断言「有 / 无『一律填 null』」：公共角色段里本来就
        // 写着「拿不准的字段一律填 null」（那是通用口径，全场景都有），按整串断言会恒假。
        assertThat(prompt).doesNotContain("location_path：一律填 null")
    }

    @Test
    fun system_photoInput_forcesNullLocationPath() {
        // V2-FR-13「位置先定后用」：位置值不由模型判断 —— 契约段要**硬性**要求 null，
        // 而不是「不提」：不提的话模型仍会照着自己见过的清单格式猜一个出来，污染草稿。
        val prompt = AiPrompt.system(AiScene.PHOTO_INPUT, categories, locations)

        assertThat(prompt).contains("一律填 null")
        assertThat(prompt).doesNotContain("从根到叶逐层分段的字符串数组")
    }

    @Test
    fun system_photoInput_asksForEveryItem() {
        // V2-FR-11：一张照片可识别多件 —— 功能段现在敢写「逐件」了（此前契约是单份草稿，
        // 写「逐件一行」会与之直接冲突，故当时刻意留白）。
        val prompt = AiPrompt.system(AiScene.PHOTO_INPUT, categories, locations)

        assertThat(prompt).contains("逐件辨认")
    }

    @Test
    fun temperature_isZero() {
        // 抽取任务要的是稳定译法，不需要发散（V2-FR-16 的开发侧常量）。
        assertThat(AiPrompt.TEMPERATURE).isEqualTo(0.0)
    }
}
