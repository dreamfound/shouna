package com.dream.shouna.util

import javax.inject.Inject
import javax.inject.Singleton
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/**
 * 拼音派生（FR-20 / ARCHITECTURE-P0 §4 P0-04 ①）：全拼 + 首字母，**离线、零网络**。
 *
 * 口径（§8.1-10 / §8.1-11）：
 * - **多音字取默认读音**（`toHanyuPinyinStringArray` 返回数组的第一个），不做声调、不做谐音纠错。
 * - **非汉字原样保留**（数字 / 字母 / 标点照抄），使 `iphone 15` 这类名称可直接按原串命中。
 * - 输出经 [TextNormalizer.normalize]，与名称检索键同源，保证比较口径一致。
 *
 * 落点（§8.1-10）：物品名 → **持久化列** `pinyin_full` / `pinyin_initial`；
 * 位置名 / 分类名不落库，由索引构建时实时调用本工具并缓存。
 *
 * 被调用方：`ItemRepositoryImpl`（写 `ItemEntity` 的拼音列）、
 *             `SearchIndex`（位置名 / 分类名实时拼音化）。
 */
@Singleton
class PinyinUtil @Inject constructor() {

    /** 全拼，如「电风扇」→ `dianfengshan`。 */
    fun full(text: String): String = TextNormalizer.normalize(joinSyllables(text) { it })

    /** 首字母，如「电风扇」→ `dfs`。 */
    fun initial(text: String): String =
        TextNormalizer.normalize(joinSyllables(text) { syllable -> syllable.take(1) })

    /** 一次算两个键，避免重复逐字扫描（建索引时的热点路径）。 */
    fun keys(text: String): Pair<String, String> =
        TextNormalizer.normalize(joinSyllables(text) { it }) to
            TextNormalizer.normalize(joinSyllables(text) { it.take(1) })

    private fun joinSyllables(text: String, transform: (String) -> String): String = buildString {
        text.forEach { ch ->
            val syllable = syllableOf(ch)
            if (syllable == null) {
                append(ch)
            } else {
                append(transform(syllable))
            }
        }
    }

    /** 单字音节；非汉字（含 ASCII、标点、emoji）返回 null，由调用方原样保留。 */
    private fun syllableOf(ch: Char): String? {
        // 快速排除：CJK 扩展 A 区起始为 U+3400，低于它的字符不必进 pinyin4j（避免无谓的格式异常开销）。
        if (ch.code < CJK_EXT_A_START) return null
        return runCatching { PinyinHelper.toHanyuPinyinStringArray(ch, FORMAT)?.firstOrNull() }
            .getOrNull()
    }

    private companion object {
        /** CJK 统一表意文字扩展 A 区起始码位。 */
        const val CJK_EXT_A_START: Int = 0x3400

        /** 小写、无声调、`ü` 记作 `v`（与检索键的 ASCII 取向一致）。 */
        val FORMAT: HanyuPinyinOutputFormat = HanyuPinyinOutputFormat().apply {
            caseType = HanyuPinyinCaseType.LOWERCASE
            toneType = HanyuPinyinToneType.WITHOUT_TONE
            vCharType = HanyuPinyinVCharType.WITH_V
        }
    }
}
