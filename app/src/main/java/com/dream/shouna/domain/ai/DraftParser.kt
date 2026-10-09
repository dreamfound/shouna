package com.dream.shouna.domain.ai

import com.dream.shouna.util.TextNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 模型原始文本 → **草稿列表**（**纯函数**，`docs/code-map/02-数据与领域.md`）。
 *
 * 【为什么是列表】`V2-FR-11`：一张照片可以识别出多件物品，各给一行草稿。文字通道同走这一条
 * 出口（结果长度为 1），免得两套形态在下游分叉。
 *
 * **结构容错**：契约约定的形态是 `{"items":[ 物品, … ]}`。但模型有时会**直接回单个物品对象**
 * （实测同 prompt 同图都会摇摆），因此两条都认：
 * - 顶层有 `items` 数组 → 逐个解析（元素不是对象的一律丢弃）；
 * - 否则 → 把顶层当作**单个物品对象**，结果为长度 1 的列表。
 *
 * **容错三级**：① 直接解析；② 失败则截取首个**括号配对完整**的 `{...}` 再解析（应付模型把 JSON
 * 包在 ``` 代码块里、或前后带一句说明的情形）；③ 再失败返 null（调用方走人工回退，不弹窗）。
 *
 * **分类白名单**：返回名归一化后必须**精确命中**传入名单；命中则回填**名单里的原名**（不回填
 * 模型的大小写 / 空格变体，保证下游与既有分类逐字一致），未命中一律 `null`——AI 绝不新建分类。
 *
 * **空草稿会被丢掉**：四字段全空的条目对用户没有任何价值（`V2-FR-09` 本来就要求「不出卡、只给
 * 提示」），留在列表里只会让调用方多写一遍过滤。全部被丢掉时返回**空列表**，而非 null ——
 * 「调用成功但没识别出东西」与「调用失败」是两件事。
 *
 * **`location_path` 降级**：模型返回单个字符串（而非数组）时当作**单元素数组**处理。
 *
 * 无状态、无依赖，可直接调用 [parse]。
 */
object DraftParser {

    private const val KEY_ITEMS = "items"
    private const val KEY_NAME = "name"
    private const val KEY_LOCATION_PATH = "location_path"

    /** 模型偶尔回 camelCase —— 一份便宜的后备键，不引入别名表。 */
    private const val KEY_LOCATION_PATH_CAMEL = "locationPath"
    private const val KEY_CATEGORY = "category"
    private const val KEY_NOTE = "note"

    private val LENIENT = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String?, categoryNames: List<String>): List<AiDraft>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val payload = decodeObject(text) ?: return null

        val items = payload[KEY_ITEMS]
        val drafts = if (items is JsonArray) {
            items.mapNotNull { element -> (element as? JsonObject)?.let { toDraft(it, categoryNames) } }
        } else {
            listOf(toDraft(payload, categoryNames))
        }
        return drafts.filterNot { it.isEmpty }
    }

    private fun toDraft(payload: JsonObject, categoryNames: List<String>): AiDraft =
        AiDraft(
            name = payload.stringOrNull(KEY_NAME),
            locationPath = payload.locationPathOrNull(),
            category = matchCategory(payload[KEY_CATEGORY]?.asStringOrNull(), categoryNames),
            note = payload.stringOrNull(KEY_NOTE),
        )

    // --- 容错解析 ---------------------------------------------------------------

    private fun decodeObject(text: String): JsonObject? {
        decodeStrict(text)?.let { return it }
        // 二级容错：逐个「{」试，取**第一个能解析出对象**的括号配对子串。字符串内的花括号不参与
        // 计数（模型可能把「收纳盒{大}」这类内容写进字段值），转义字符单独处理。
        // 之所以要循环而不是只试第一处：模型爱在 JSON 前写一句带花括号的说明（「说明{……}：{…}」）。
        var searchFrom = 0
        while (true) {
            val start = text.indexOf('{', searchFrom)
            if (start < 0) return null
            val end = matchingBraceEnd(text, start)
            if (end < 0) return null
            decodeStrict(text.substring(start, end + 1))?.let { return it }
            searchFrom = start + 1
        }
    }

    /** 与 [start] 处的 `{` 配对的那个 `}` 的下标；不配对返回 -1。 */
    private fun matchingBraceEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                inString && char == '\\' -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> depth++
                else -> if (char == '}') {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun decodeStrict(text: String): JsonObject? =
        runCatching { LENIENT.parseToJsonElement(text).jsonObject }.getOrNull()

    // --- 字段级口径 -------------------------------------------------------------

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = this[key] ?: return null
        return element.asTextOrNull()
    }

    /**
     * 期望 `["客厅","抽屉"]`；模型回单个字符串时**降级为单元素数组**。
     * **只认字符串元素**：数字 / 布尔 / 嵌套对象一律丢弃 —— 层名要与既有位置逐字比对，
     * 拿一个「3」去匹配只会凭空多出一层待新建节点。
     */
    private fun JsonObject.locationPathOrNull(): List<String>? {
        val element: JsonElement = this[KEY_LOCATION_PATH] ?: this[KEY_LOCATION_PATH_CAMEL] ?: return null
        if (element is JsonNull) return null
        val layers = when (element) {
            is JsonArray -> element.mapNotNull { it.asStringOrNull() }
            is JsonPrimitive -> listOfNotNull(element.asStringOrNull())
            else -> emptyList()
        }
        return layers.map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
    }

    /** 白名单校验：归一化后精确相等才认；回填**名单里的原名**。 */
    private fun matchCategory(raw: String?, categoryNames: List<String>): String? {
        val returned = raw?.trim().orEmpty()
        if (returned.isEmpty()) return null
        val target = TextNormalizer.normalize(returned)
        return categoryNames.firstOrNull { TextNormalizer.normalize(it) == target }
    }

    /** 文本取值：`null` 字面量 / 空白 → null；数字与布尔（模型偶尔如此）按其字面量取。 */
    private fun JsonElement.asTextOrNull(): String? {
        if (this is JsonNull) return null
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.content.trim().takeIf { it.isNotEmpty() }
    }

    /** 只认 JSON 字符串元素（用于位置层名与分类）。 */
    private fun JsonElement.asStringOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive is JsonNull || !primitive.isString) return null
        return primitive.content.trim().takeIf { it.isNotEmpty() }
    }
}
