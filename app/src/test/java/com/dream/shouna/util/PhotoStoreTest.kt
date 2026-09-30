package com.dream.shouna.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-16 的图片采样率换算（[sampleSizeFor]）单测。
 *
 * 只测这一个**纯函数**：`PhotoStore` 的其余成员都要 `BitmapFactory`，属仪器测试的地盘
 * （与「仓库层逻辑进 JVM 单测、真机能力进 androidTest」的既有分工一致）。
 *
 * 这条函数值得单测是因为它错了**不会报错、只会变慢或爆内存**：采样率算小 → 解一张
 * 4000×3000 的原图进 80dp 的缩略图（掉帧 / OOM）；算大 → 照片糊成一团。两种都不是崩溃，
 * 不会有任何日志，只能靠断言把它钉住。
 */
class PhotoStoreTest {

    @Test
    fun sampleSizeFor_halvesUntilBothSidesBelowTwiceTheTarget() {
        // 手机原图 4000×3000 要放进 240px 的缩略图框：16 倍采样 → 250×187。
        assertEquals(16, sampleSizeFor(width = 4000, height = 3000, maxPx = 240))
    }

    @Test
    fun sampleSizeFor_keepsLongestSideWithinTargetWindow() {
        val cases = listOf(
            Triple(4000, 3000, 240),
            Triple(1200, 900, 240),
            Triple(4000, 3000, 720),
            Triple(8000, 6000, 1080),
        )
        cases.forEach { (w, h, maxPx) ->
            val sample = sampleSizeFor(w, h, maxPx)
            val longSide = maxOf(w, h).toFloat() / sample
            // 语义：采样后较长边落在 [maxPx, 2 * maxPx)。低于下界就是糊，高于上界就是白解码。
            assertTrue(
                "($w, $h) @ maxPx=$maxPx → sample=$sample, 长边=$longSide 超出 [$maxPx, ${maxPx * 2})",
                longSide >= maxPx && longSide < maxPx * 2,
            )
        }
    }

    @Test
    fun sampleSizeFor_neverUpscalesSmallImages() {
        // 比目标框还小的图不该被「采样」放大 —— inSampleSize 只能 ≥ 1。
        assertEquals(1, sampleSizeFor(width = 100, height = 80, maxPx = 240))
        assertEquals(1, sampleSizeFor(width = 240, height = 240, maxPx = 240))
    }

    @Test
    fun sampleSizeFor_returnsOneForNonPositiveTarget() {
        // 非法目标值（0 / 负数）不该导致死循环或负数采样率，一律退化成不采样。
        assertEquals(1, sampleSizeFor(width = 4000, height = 3000, maxPx = 0))
        assertEquals(1, sampleSizeFor(width = 4000, height = 3000, maxPx = -1))
    }
}
