package com.dream.shouna.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * FR-16 的图片仓库：照片本体一律放在 `filesDir/photos/`，数据库里只记**文件名**。
 *
 * 【为什么是应用私有目录】与「数据仅存本机、无任何备份 / 迁移通道」（NFR-09 / §9.1）保持一致：
 * 私有目录随卸载一起消失，不留在共享存储里被其他应用读到、也不会在换机后残留半个副本。
 * 代价是明确的 —— 卸载即丢，与数据库同样的命运。
 *
 * 【为什么不用外部存储 + `WRITE_EXTERNAL_STORAGE`】那会引入第二个（且在 Android 10+ 已受限的）
 * 存储权限，只为省下「路径随安装位置变化」这一件事不值。
 *
 * 【文件名只存名字、不存绝对路径】`filesDir` 的绝对路径在换机、换安装位置（内部 ↔ adoptable
 * storage）后会变，文件名不会；路径一律由 [fileOf] 现算。
 *
 * 被调用方**一律在 UI 层**（ViewModel 不碰文件，见 `docs/code-map/05-改动须知.md` §1-31 第 ① 条）：
 * `ui/component/PhotoInputRow.kt`（建 / 删 / 缩略图）、`ui/screen/item/ItemDetailScreen.kt`（预览解码 + 条件删除）、
 * `ui/screen/item/ItemEditScreen.kt`（删废弃图）、`ui/screen/photoai/PhotoAiIntakeScreen.kt`（离开本页删未落库的临时图）、
 * `util/AiImageEncoder.kt`（编码前解码）。
 */
object PhotoStore {

    /** 私有目录下的子目录名（`res/xml/file_paths.xml` 的白名单与它一一对应）。 */
    private const val DIR_NAME = "photos"

    private const val TAG = "PhotoStore"

    /** 生成一个**空文件**作为系统相机的写入落点，并返回它。 */
    fun newCaptureTarget(context: Context): File {
        val dir = ensureDir(context)
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.createNewFile()
        return file
    }

    /**
     * 把相册选中的图片**复制**进私有目录，返回新文件名；失败（流打不开 / 空文件）返回 null。
     *
     * 复制而不是引用原始 `content://` URI：那个 URI 的授权是临时的，进程一重启就失效，
     * 而物品要长期可见。
     */
    fun importFromUri(context: Context, uri: Uri): String? = try {
        val dir = ensureDir(context)
        val name = "${UUID.randomUUID()}.jpg"
        val target = File(dir, name)
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
            true
        } ?: false
        // 0 字节文件在预览时表现为「一片空白」，不如当作失败，让 UI 提示重选。
        if (!copied || target.length() == 0L) {
            target.delete()
            null
        } else {
            name
        }
    } catch (t: Throwable) {
        Log.w(TAG, "导入相册图片失败", t)
        null
    }

    /** 删除一张照片（name 为空或文件已不在时静默返回）。 */
    fun delete(context: Context, name: String?) {
        if (name.isNullOrEmpty()) return
        runCatching {
            val file = fileOf(context, name)
            if (file.exists()) file.delete()
        }
    }

    fun fileOf(context: Context, name: String): File = File(ensureDir(context), name)

    /**
     * 采样解码：`maxPx` 是**目标边长上限**。手机原图常有 4000×3000 以上，按原尺寸解码
     * 一张就够把低端机（本项目的目标机型）撑爆，因此一律带 `inSampleSize` 解码。
     *
     * 读不出（文件缺失 / 不是图片）返回 null，由 UI 退化成「不显示照片」而不是崩。
     */
    fun decode(context: Context, name: String, maxPx: Int): Bitmap? = try {
        val file = fileOf(context, name)
        if (!file.exists()) {
            null
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPx)
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "解码照片失败", t)
        null
    }

    private fun ensureDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}

/**
 * 计算 2 的幂次采样率：解码后的宽高都不小于 `maxPx` 的前提下，尽量少降采样。
 *
 * 抽到**文件级**（而不是塞在 [PhotoStore] 里）是为了能在 JVM 单测里直接跑 —— 它不碰任何
 * Android 类型，而 `PhotoStore` 的其它成员依赖 `BitmapFactory`。
 *
 * 取 2 的幂是 `inSampleSize` 的约定：非 2 的幂会被系统向下取整到 2 的幂，自己算准免得心里没底。
 */
internal fun sampleSizeFor(width: Int, height: Int, maxPx: Int): Int {
    if (maxPx <= 0) return 1
    var sample = 1
    var w = width
    var h = height
    while (w / 2 >= maxPx || h / 2 >= maxPx) {
        w /= 2
        h /= 2
        sample *= 2
    }
    return sample
}
