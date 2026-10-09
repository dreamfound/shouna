package com.dream.shouna.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 照片 → **裸 base64**（不含 `data:` 前缀）的编码器，供照片通道（`V2-FR-11`）使用。
 *
 * 【为什么单独一个工具而不是塞进 `DeepSeekAiClient`】分层：**文件 IO 全部留在 UI 层**是
 * `util/PhotoStore` 立下的规矩（仓库层与网络层都不依赖 `Context`）。本类在中间，把「文件名」
 * 变成「可上行的串」，于是 `AiRequest.imageBase64` 只是一个字符串，网络层拿到就能拼 data URL。
 *
 * 【尺寸与质量的口径从哪来】2026-10-08 实测：**图片体积几乎不影响耗时** —— 581 KB base64 的大图
 * 只花 1.4 秒，而同一时刻的耗时几乎全部由推理 token 决定（拟合 `耗时 ≈ 4.72 ms × 推理token`，
 *  r = 0.9974）。因此这里**不做激进的压缩**（那会白白损失识别率），只把边长与质量收到一个
 *  合理的量级：既够模型看清物品，又不会把移动网络的上行撑爆。
 *
 * 解码失败（文件缺失 / 不是图片）返回 null —— 调用方据此提示用户，而不是把空串发出去。
 */
object AiImageEncoder {

    /** 长边上限。1024 对「看清一件物品」足够，且远低于手机原图的 4000+ 边长。 */
    const val MAX_EDGE_PX = 1024

    /** JPEG 质量。80 是「肉眼看不出损失」与「体积」之间的常用折中。 */
    const val JPEG_QUALITY = 80

    private const val TAG = "AiImageEncoder"

    /** 读 `filesDir/photos/<photoName>` → 限长边 → JPEG → base64；任一步失败返回 null。 */
    fun encodeBase64(context: Context, photoName: String): String? {
        return try {
            val decoded = PhotoStore.decode(context, photoName, MAX_EDGE_PX) ?: return null
            val scaled = scaleToMaxEdge(decoded, MAX_EDGE_PX)
            val bytes = ByteArrayOutputStream().use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                stream.toByteArray()
            }
            // 缩放过就释放中间位图；原图是否被复用看 scaleToMaxEdge 的返回，故分开判。
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            if (bytes.isEmpty()) null else Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "照片编码为 base64 失败", t)
            null
        }
    }

    /**
     * 等比缩到长边 = [maxEdge]；已经在范围内则**原样返回**（返回同一实例，调用方须自行判断是否需要
     * 释放，见 [encodeBase64] 的 `!==` 判断）。
     */
    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
