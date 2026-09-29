package com.dream.shouna.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * FR-20 单测（纯 JVM）：拼音全拼 / 首字母的派生口径。
 *
 * 覆盖四条约定（ARCHITECTURE-P0 §8.1-10 / §8.1-11）：
 * ① 全拼与首字母；② 多音字取**默认读音**且结果稳定；③ **非汉字原样保留**；
 * ④ 输出经 `TextNormalizer.normalize`（与检索键同源）。
 *
 * 不覆盖：不做声调、不做谐音纠错 —— 那是「不做什么」，用边界用例（`zhangsan` 与 `zhang-san`）
 * 间接体现：本工具不做分隔符归一之外的任何改写。
 */
class PinyinUtilTest {

    private val pinyin = PinyinUtil()

    @Test
    fun fullAndInitial() {
        assertThat(pinyin.full("电风扇")).isEqualTo("dianfengshan")
        // 首字母按**字序**：电(d) 风(f) 扇(s) = dfs。
        assertThat(pinyin.initial("电风扇")).isEqualTo("dfs")
        assertThat(pinyin.keys("电风扇")).isEqualTo("dianfengshan" to "dfs")
    }

    @Test
    fun multiplePronunciationTakesTheDefaultReadingAndIsStable() {
        // 多音字（长：chang / zhang）取数组首项；同一输入两次调用结果必须一致（可缓存的前提）。
        val first = pinyin.full("长")
        assertThat(first).isAnyOf("chang", "zhang")
        assertThat(pinyin.full("长")).isEqualTo(first)
        assertThat(pinyin.initial("长")).isEqualTo(pinyin.full("长").take(1))
    }

    @Test
    fun nonHanCharactersAreKeptAsIs() {
        // 数字 / 字母 / 标点 / 空格照抄 —— 「iphone 15」可按原串直接命中。
        assertThat(pinyin.full("iphone 15")).isEqualTo("iphone 15")
        assertThat(pinyin.initial("iphone 15")).isEqualTo("iphone 15")
        // 中英混排：汉字转拼音、非汉字原样，顺序不变。
        assertThat(pinyin.keys("iPhone 充电器")).isEqualTo("iphone chongdianqi" to "iphone cdq")
    }

    @Test
    fun outputIsNormalizedLikeSearchKeys() {
        // 全角字母 → 半角、统一小写（与 `TextNormalizer.normalize` 同源）。
        assertThat(pinyin.full("ＵＳＢ 线")).isEqualTo("usb xian")
        // ü 记为 v（ASCII 取向，与检索键一致）。
        assertThat(pinyin.full("女")).isEqualTo("nv")
        // 连续空白折叠（非汉字原样保留 → 空白先被留下，再由归一化折叠为单个空格）。
        assertThat(pinyin.full("充   电器")).isEqualTo("chong dianqi")
    }

    @Test
    fun emptyInputProducesEmptyKeys() {
        assertThat(pinyin.keys("")).isEqualTo("" to "")
        assertThat(pinyin.keys("   ")).isEqualTo("" to "")
    }
}
