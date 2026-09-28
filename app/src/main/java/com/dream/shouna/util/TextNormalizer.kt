package com.dream.shouna.util

/**
 * 文本归一化（ARCHITECTURE §3.2 `normalizedName`、§4 F1-03 ①）：规则与检索键同源。
 * 规则：全角→半角、大小写统一、空白折叠。无拼音（F1 不做拼音检索）。
 *
 * 被调用方：`deriveNormalizedName`（ItemRepositoryImpl）→ `normalizedName`
 *           `SearchScorer` / `SearchIndex` → 查询串归一化
 */
object TextNormalizer {
    /** 连续空白（含制表 / 换行）。半角化之后使用，故无需再处理全角空格 U+3000。 */
    private val WHITESPACE_RUN = Regex("\\s+")

    /** 归一化：用于 `normalizedName` 与查询串，两侧必须同一函数。 */
    fun normalize(raw: String): String {
        // 顺序固定：先全角→半角，再折叠空白，最后统一大小写。
        // 顺序颠倒会让全角空格（U+3000）漏过空白折叠。
        return collapseWhitespace(toHalfWidth(raw)).lowercase()
    }

    /** 检索谓词：归一化子串匹配（「风扇」命中「电风扇」）。 */
    fun containsNormalized(normalizedName: String, normalizedQuery: String): Boolean {
        // 空串按「未输入」处理，由调用方在进入检索前拦截；此处保持纯子串语义。
        return normalizedName.contains(normalizedQuery)
    }

    /** 空白折叠。 */
    fun collapseWhitespace(raw: String): String {
        return raw.replace(WHITESPACE_RUN, " ").trim()
    }

    /**
     * 与 [normalize] 同规则的**保位变体**：额外返回「归一化串每个下标 ← 原串下标」的映射，
     * 供搜索结果行把高亮区间换算回原串（`SearchScorer.highlightRange`）。
     *
     * 与 [normalize] 的一致性：先全角→半角（1:1，不改长度）、空白折叠、逐字符小写。
     * 与 `String.lowercase()` 的差别仅在「小写后长度会变的字符」（如 `İ`），对本工程的中英文
     * 数据不产生差异；这也是此处采用逐字符小写的原因 —— 保证映射恒为一一对应。
     */
    fun normalizeWithSourceIndex(raw: String): Pair<String, IntArray> {
        // 全角→半角是 1:1 映射，此后 half 的下标即原串下标。
        val half = toHalfWidth(raw)
        val builder = StringBuilder(half.length)
        val sourceIndices = ArrayList<Int>(half.length)
        var pendingSpace = false
        var started = false

        for (index in half.indices) {
            val char = half[index]
            if (char.isWhitespace()) {
                // 折叠：多空白只留一个，且首部空白丢弃（等价 trim）。
                if (started) pendingSpace = true
            } else {
                if (pendingSpace) {
                    builder.append(' ')
                    sourceIndices.add(index - 1)
                    pendingSpace = false
                }
                started = true
                builder.append(char.lowercaseChar())
                sourceIndices.add(index)
            }
        }
        return builder.toString() to sourceIndices.toIntArray()
    }

    /** 全角→半角。 */
    fun toHalfWidth(raw: String): String {
        val builder = StringBuilder(raw.length)
        for (char in raw) {
            when {
                // 全角空格单独处理：与 U+FF01..U+FF5E 的偏移量不同。
                char == FULL_WIDTH_SPACE -> builder.append(' ')
                char.code in FULL_WIDTH_RANGE -> builder.append((char.code - FULL_HALF_OFFSET).toChar())
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }

    /** 全角空格 U+3000。 */
    private const val FULL_WIDTH_SPACE = '\u3000'

    /** 全角可打印区间：！ (U+FF01) ～ ～ (U+FF5E)。 */
    private val FULL_WIDTH_RANGE = 0xFF01..0xFF5E

    /** 全角与半角的码点偏移量。 */
    private const val FULL_HALF_OFFSET = 0xFEE0
}
