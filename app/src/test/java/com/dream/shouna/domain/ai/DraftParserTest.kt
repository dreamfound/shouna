package com.dream.shouna.domain.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * AI 单测（纯 JVM，不触网）：模型原始文本 → **草稿列表**的容错与白名单口径。
 *
 * 覆盖 `docs/code-map/02-数据与领域.md` AI-01 验收里的 7 条（合法 JSON / 代码块包裹 / 前后多余文本 / 字段缺失 /
 * 分类不在名单 / `location_path` 为字符串 / 完全非 JSON），加上本轮的新形态：
 * **`{"items":[…]}` 数组**（`V2-FR-11` 一张照片多件物品）以及「空草稿被丢掉」的口径。
 */
class DraftParserTest {

    private val categories = listOf("电子配件", "厨房用品", "杂物")

    /** 单件场景的便捷取值：文字通道的绝大多数断言只关心第一条。 */
    private fun firstDraft(raw: String?): AiDraft? = DraftParser.parse(raw, categories)?.firstOrNull()

    @Test
    fun parse_validJson_keepsEveryField() {
        val draft = firstDraft(
            """{"name":"充电器","location_path":["客厅","抽屉"],"category":"电子配件","note":"白色 65W"}""",
        )

        assertThat(draft).isNotNull()
        assertThat(draft?.name).isEqualTo("充电器")
        assertThat(draft?.locationPath).containsExactly("客厅", "抽屉").inOrder()
        assertThat(draft?.category).isEqualTo("电子配件")
        assertThat(draft?.note).isEqualTo("白色 65W")
        assertThat(draft?.isEmpty).isFalse()
    }

    @Test
    fun parse_singleObject_returnsListOfOne() {
        // 文字通道模型回的是单个对象 —— 出口统一是列表，长度为 1。
        assertThat(DraftParser.parse("""{"name":"耳机"}""", categories)).hasSize(1)
    }

    @Test
    fun parse_itemsArray_returnsOneDraftPerItem() {
        // V2-FR-11：一张照片识别出多件，各一行草稿。
        val drafts = DraftParser.parse(
            """{"items":[{"name":"杯子","category":"杂物","note":"红色"},""" +
                """{"name":"书","category":"电子配件"},""" +
                """{"name":"笔","note":"绿色"}]}""",
            categories,
        )

        assertThat(drafts).hasSize(3)
        assertThat(drafts!!.map { it.name }).containsExactly("杯子", "书", "笔").inOrder()
    }

    @Test
    fun parse_emptyItemsArray_returnsEmptyList() {
        // 契约约定「一件都没识别出」回 {"items":[]} —— 这是**成功**，不是失败（失败是 null）。
        val drafts = DraftParser.parse("""{"items":[]}""", categories)

        assertThat(drafts).isNotNull()
        assertThat(drafts).isEmpty()
    }

    @Test
    fun parse_itemsWithNonObjectElements_dropsThem() {
        // 数组里混进字符串 / 数字时不崩，静默丢弃 —— 位置层名与分类都只认能解析出字段的对象。
        val drafts = DraftParser.parse("""{"items":["乱码",42,{"name":"台灯"}]}""", categories)

        assertThat(drafts).hasSize(1)
        assertThat(drafts!!.first().name).isEqualTo("台灯")
    }

    @Test
    fun parse_itemsWhitelistAppliesPerItem() {
        // 白名单逐条生效：名单外的那个分类置空，名单内的原样回填。
        val drafts = DraftParser.parse(
            """{"items":[{"name":"哑铃","category":"健身器材"},{"name":"锅","category":"厨房用品"}]}""",
            categories,
        )

        assertThat(drafts!!.map { it.category }).containsExactly(null, "厨房用品").inOrder()
    }

    @Test
    fun parse_markdownFencedJson_isAccepted() {
        val draft = firstDraft(
            "```json\n{\"name\":\"电风扇\",\"location_path\":[\"储物间\"]}\n```",
        )

        assertThat(draft?.name).isEqualTo("电风扇")
        assertThat(draft?.locationPath).containsExactly("储物间")
    }

    @Test
    fun parse_surroundingProse_isAccepted() {
        val draft = firstDraft(
            "好的，整理结果如下：{\"name\":\"螺丝刀\",\"category\":\"杂物\"} 还需要别的吗？",
        )

        assertThat(draft?.name).isEqualTo("螺丝刀")
        assertThat(draft?.category).isEqualTo("杂物")
    }

    @Test
    fun parse_bracesInsideString_doNotBreakExtraction() {
        // 二级容错按括号配对扫描，必须忽略字符串内的花括号，否则会截出半截 JSON。
        val draft = firstDraft(
            "说明{这个不算}：{\"name\":\"收纳盒{大}\",\"location_path\":[\"家\"]}",
        )

        assertThat(draft?.name).isEqualTo("收纳盒{大}")
    }

    @Test
    fun parse_missingFields_areNull() {
        val draft = firstDraft("""{"name":"耳机"}""")

        assertThat(draft?.name).isEqualTo("耳机")
        assertThat(draft?.locationPath).isNull()
        assertThat(draft?.category).isNull()
        assertThat(draft?.note).isNull()
    }

    @Test
    fun parse_allFieldsMissing_isDroppedFromResult() {
        // 四字段全空的条目对用户没有价值（V2-FR-09 本来就「不出卡、只给提示」），
        // 在解析出口就丢掉 —— 调用方不必再写一遍过滤。
        assertThat(DraftParser.parse("""{"name":null,"location_path":null,"category":null,"note":null}""", categories))
            .isEmpty()
        // 同理：模型回一个「有 items 键但里面全是空对象」的形态。
        assertThat(DraftParser.parse("""{"items":[{"name":null},{"note":null}]}""", categories)).isEmpty()
    }

    @Test
    fun parse_categoryOutsideWhitelist_isNulled() {
        // AI 绝不新建分类：名单外的分类一律置空，绝不写进草稿。
        val draft = firstDraft("""{"name":"哑铃","category":"健身器材"}""")

        assertThat(draft?.category).isNull()
    }

    @Test
    fun parse_categoryMatchingAfterNormalization_returnsWhitelistOriginal() {
        // 归一化涵盖首尾空白 / 大小写 / 全半角；**回填名单里的原名**，保证与既有分类逐字一致。
        // （词内空格不在归一化范围内：「厨房 用品」仍算未命中 → 置空，宁缺勿错配。）
        val draft = firstDraft("""{"name":"锅","category":"  厨房用品  "}""")

        assertThat(draft?.category).isEqualTo("厨房用品")
    }

    @Test
    fun parse_categoryWhenWhitelistEmpty_isNulled() {
        val draft = DraftParser.parse("""{"name":"锅","category":"厨房用品"}""", emptyList())?.firstOrNull()

        assertThat(draft?.category).isNull()
    }

    @Test
    fun parse_locationPathAsPlainString_degradesToSingleElement() {
        val draft = firstDraft("""{"name":"锤子","location_path":"阳台"}""")

        assertThat(draft?.locationPath).containsExactly("阳台")
    }

    @Test
    fun parse_locationPathWithNonTextElements_dropsThem() {
        val draft = firstDraft("""{"name":"箱子","location_path":["储物间",3,""]}""")

        assertThat(draft?.locationPath).containsExactly("储物间")
    }

    @Test
    fun parse_camelCaseLocationPath_isAccepted() {
        val draft = firstDraft("""{"name":"雨伞","locationPath":["玄关","伞架"]}""")

        assertThat(draft?.locationPath).containsExactly("玄关", "伞架").inOrder()
    }

    @Test
    fun parse_nonJson_returnsNull() {
        assertThat(DraftParser.parse("抱歉，我不太明白你的意思。", categories)).isNull()
    }

    @Test
    fun parse_blankInput_returnsNull() {
        assertThat(DraftParser.parse(null, categories)).isNull()
        assertThat(DraftParser.parse("   ", categories)).isNull()
    }
}
