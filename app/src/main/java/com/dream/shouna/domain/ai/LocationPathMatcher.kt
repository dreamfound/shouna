package com.dream.shouna.domain.ai

import com.dream.shouna.util.PinyinUtil
import com.dream.shouna.util.TextNormalizer

/**
 * 参与匹配的最小位置节点。
 *
 * 为什么不在签名里用 `Location` / `LocationTreeRow`（降耦合）：匹配是一段纯算法，不该知道
 * 「位置从 Room 来、带路径文本、带计数」这些事。调用方（ViewModel）负责把位置树映射成本类型，
 * 匹配结果再映射回 id —— 算法因此可独立测试、独立演进。
 */
data class AiLocationNode(
    val id: String,
    val name: String,
    /** 父级；根级 = null。 */
    val parentId: String?,
)

/**
 * 匹配结果。
 *
 * - [matchedIds]：逐层命中的节点 id（按层序，父在前）；
 * - [pendingNames]：**待新建**的层名（按父链顺序）——它只是确认卡上的一个**标记**，
 *   真正的 `create(name, parentId)` 发生在用户点「填入」之后（`docs/code-map/02-数据与领域.md` 硬边界 2）。
 */
data class LocationMatch(
    val matchedIds: List<String> = emptyList(),
    val pendingNames: List<String> = emptyList(),
) {
    /** 最深的命中节点 id —— 直接可作为「选定位置」；null = 一层都没命中。 */
    val matchedId: String? get() = matchedIds.lastOrNull()

    /** 是否需要逐层新建。 */
    val hasPending: Boolean get() = pendingNames.isNotEmpty()

    /** 完全未命中（连根层都对不上）。 */
    val nothingMatched: Boolean get() = matchedIds.isEmpty()
}

/**
 * 位置路径 → 命中链 / 待新建层（`docs/code-map/02-数据与领域.md`，**纯函数**，AI-01 子步骤⑥）。
 *
 * 三条匹配口径：
 * 1. **逐层下沉**：只在「当前父节点的**直接**子节点」里找；未命中则该层与其后**所有层**一并记为
 *    待新建（父节点尚不存在，后面几层再匹配也没有意义）；
 * 2. **只认精确相等**：归一化后相等（[TextNormalizer]），或**拼音全拼**相等（处理「keting」这类
 *    同音输入）。**不做包含匹配、不做首字母缩写**——否则「客厅」会命中「客厅储物柜」；
 * 3. **同层多命中取第一个**：位置树同级不重名由应用层软校验保证（`PRD.md` §7 A-3），实际不会分歧。
 *
 * [pinyin] 做成可替换的构造参数（默认即生产实现）：算法本身不关心拼音怎么算，单测可注入替身。
 */
class LocationPathMatcher(private val pinyin: PinyinUtil = PinyinUtil()) {

    fun match(path: List<String>?, nodes: List<AiLocationNode>): LocationMatch {
        val layers = path?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return LocationMatch()
        if (layers.isEmpty()) return LocationMatch()

        val matchedIds = ArrayList<String>(layers.size)
        val pendingNames = ArrayList<String>(layers.size)
        var parentId: String? = null

        for (layer in layers) {
            if (pendingNames.isNotEmpty()) {
                // 上面某一层没命中 ⇒ 父链断了，其后各层一律待新建。
                pendingNames += layer
                continue
            }
            val hit = nodes.firstOrNull { it.parentId == parentId && sameLayer(layer, it.name) }
            if (hit == null) {
                pendingNames += layer
            } else {
                matchedIds += hit.id
                parentId = hit.id
            }
        }
        return LocationMatch(matchedIds = matchedIds, pendingNames = pendingNames)
    }

    /** 归一化相等，或拼音全拼相等（非汉字原样保留，故 "keting" 与「客厅」两侧同形）。 */
    private fun sameLayer(candidate: String, existing: String): Boolean {
        if (TextNormalizer.normalize(candidate) == TextNormalizer.normalize(existing)) return true
        return pinyin.full(candidate) == pinyin.full(existing)
    }
}
