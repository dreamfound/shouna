package com.dream.shouna.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * F1-02 单测 ①：归一化用例（纯 JVM，无 Robolectric）。
 *
 * 被测调用：
 *   TextNormalizer.normalize(raw)                     —— 直接
 *   TextNormalizer.containsNormalized(n, q)           —— 直接
 *   TextNormalizer.toHalfWidth / collapseWhitespace   —— 经 normalize 间接覆盖（本类不单独调用）
 */
class TextNormalizerTest {

    @Test
    fun normalize_collapsesWhitespace() {
        assertThat(TextNormalizer.normalize("  电   风扇 ")).isEqualTo("电 风扇")
        assertThat(TextNormalizer.normalize("电\t\n风扇")).isEqualTo("电 风扇")
    }

    @Test
    fun normalize_convertsFullWidthToHalfWidth() {
        assertThat(TextNormalizer.normalize("Ａ１")).isEqualTo("a1")
        // 全角空格 U+3000 必须与半角空格折叠到同一结果。
        assertThat(TextNormalizer.normalize("电\u3000风扇")).isEqualTo("电 风扇")
    }

    @Test
    fun normalize_isCaseInsensitive() {
        assertThat(TextNormalizer.normalize("iPhone")).isEqualTo(TextNormalizer.normalize("IPHONE"))
        assertThat(TextNormalizer.normalize("iPhone")).isEqualTo("iphone")
    }

    @Test
    fun normalize_isIdempotent() {
        val raw = "  ＦＡＮ   Test\u3000 42  "
        val once = TextNormalizer.normalize(raw)
        assertThat(TextNormalizer.normalize(once)).isEqualTo(once)
    }

    @Test
    fun containsNormalized_matchesSubstring() {
        // 「风扇」命中「电风扇」
        val normalizedName = TextNormalizer.normalize("电风扇")
        assertThat(TextNormalizer.containsNormalized(normalizedName, TextNormalizer.normalize("风扇")))
            .isTrue()
        // 全角 / 大小写差异不阻断命中。
        assertThat(TextNormalizer.containsNormalized(normalizedName, TextNormalizer.normalize("電風扇")))
            .isFalse()
        assertThat(TextNormalizer.containsNormalized(TextNormalizer.normalize("USB 线"), "usb")).isTrue()
    }
}
