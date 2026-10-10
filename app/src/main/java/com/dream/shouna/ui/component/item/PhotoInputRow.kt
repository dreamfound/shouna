package com.dream.shouna.ui.component.item

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dream.shouna.util.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FR-16 的照片输入行：**拍照**（系统相机）／**从相册选**／预览／移除。
 *
 * 【零权限】两条路都不给本应用加权限：
 * - 拍照走 `ACTION_IMAGE_CAPTURE`（`TakePicture` 契约）—— 应用**不声明 `CAMERA`**。这里有个
 *   反直觉的坑：一旦在清单里声明了 `CAMERA`，系统反而要求它已被授予才能用这个 Intent；
 *   不声明则由相机应用自己持有，本应用只拿到一张写好的图。
 * - 相册走 `PickVisualMedia`（Android 13+ 的系统照片选择器）—— 同样零权限；老系统上该契约
 *   会自动回落到 `ACTION_OPEN_DOCUMENT` 一类实现，依旧不需要权限。
 *
 * 【文件生命周期归谁】图片的建 / 删都在**本组件**里完成（它是系统选择器的适配层），上层只拿到
 * 一个文件名（`PhotoStore` 的产物）—— `ItemRepository` 保持「不碰文件系统」。三条路径：
 * - 拍照成功 → 删掉被替换的旧文件 → 上报新名字；
 * - 拍照取消 → 删掉自己刚建的空文件（否则私有目录会攒下 0 字节垃圾）；
 * - 相册选图 → 复制进私有目录 → 删旧文件 → 上报新名字。
 *
 * 【已知取舍：孤儿文件】选了照片却没保存就离开这一页时，那**一张**文件会留在私有目录里
 * 无人引用（保存成功时 `photoName` 会被清空，所以已入库的照片不受影响）。
 * 不在 `onDispose` 里清理是刻意的：页面因**旋转 / 进程内重组**被销毁重建时 `onDispose` 同样会跑，
 * 那时照片还没保存、却还是「在用」的，删掉就成了随机丢图 —— 用一个最多几百 KB 的孤儿文件
 * 换「绝不误删用户刚拍的照片」，这个交易划算。真要收干净，应在下次启动时做一次
 * 「文件 vs 数据库」的对账扫描，那是另一个量级的改动。
 *
 * 【P1-07 新增 [deleteReplacedImmediately]：编辑页必须关掉它】
 * 本组件默认在「选中新图」的瞬间就删掉被替换的旧文件 —— 录入场景（物品尚不存在）无害，
 * 但**编辑场景是错的**：用户选完新图又按返回不保存时，库里 `photo_path` 仍指向那个**已被删除**
 * 的旧文件，详情页从此永远显示不出照片。
 *
 * 关掉后（编辑页）：三条替换 / 移除路径都不删文件，旧文件名的删除责任交给调用方
 * ——它在**保存成功之后**才删（此时库里已经换成新名字，删旧文件才是安全的）。
 * 代价与上面同一个孤儿文件取舍一致：取消保存会留下一个无人引用的文件，但不丢图。
 *
 * 【2026-10-09 新增 [canDeleteFile]：照片 AI 页必须设闸】那一页的图可能已经被「确认」过一次、
 * 后台正在写库（`photo_path` 还没进库）—— 此时无条件删文件，库里那条记录的照片就成了破图。
 * 设闸后由调用方判定「能不能删」（照片 AI 页给的是「无在途落库 **且** 库中零引用」）。
 *
 * 被调用方：`ui/screen/add/QuickAddScreen.kt`（默认值）、`ui/screen/item/ItemEditScreen.kt`
 * （传 false）、`ui/screen/photoai/PhotoAiIntakeScreen.kt`（传 [canDeleteFile]）。
 */
@Composable
fun PhotoInputRow(
    photoName: String?,
    onPhotoChanged: (String?) -> Unit,
    enabled: Boolean = true,
    /**
     * 被替换 / 被移除的旧照片文件是否**当场删除**。
     *
     * `true`（默认）= 录入页语义：图片还没入库，旧文件立刻无用，删掉最省空间。
     * `false` = 编辑页语义：**延迟到保存成功后**由调用方删，取消编辑不产生任何删除。
     */
    deleteReplacedImmediately: Boolean = true,
    /**
     * **删文件之前的最后一道闸**：返回 `true` 才真的删。
     *
     * `null`（默认）= 不设闸，直接删 —— 录入页 / 编辑页的既有语义（那时 `photoName` 必然是
     * 还没落库的临时图，库中引用恒为 0）。
     * 照片 AI 页传入判据：那张图可能已经被「确认」过一次、后台正在写库（引用还没进库），
     * 无条件删会把库里记录的照片弄成破图（见 `PhotoAiIntakeViewModel.canDeletePhotoFile`）。
     */
    canDeleteFile: (suspend (String) -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 交给系统相机之前先记下目标文件名：回调只告诉「成没成」，不说写到哪。
    var pendingCaptureName by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    // 本组件**唯一的删文件出口**：三条路径（拍照替换 / 相册替换 / 移除）都走这里，于是「要不要
    // 设闸」这件事只需要写一次。设了闸就先问、问过才删；没设闸直接删（既有语义，一行不改）。
    fun deletePhotoFile(name: String?) {
        if (name == null) return
        val gate = canDeleteFile
        if (gate == null) {
            PhotoStore.delete(context, name)
        } else {
            scope.launch {
                if (gate(name)) PhotoStore.delete(context, name)
            }
        }
    }

    val captureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { saved ->
        val name = pendingCaptureName
        pendingCaptureName = null
        if (saved && name != null) {
            if (deleteReplacedImmediately && photoName != null && photoName != name) {
                deletePhotoFile(photoName)
            }
            onPhotoChanged(name)
        } else {
            // 这一条**与开关无关**：删的是本组件刚建出来的那个空文件（拍照被取消），
            // 它从未被上报给上层、也不属于任何人 —— 留着只会攒 0 字节垃圾。
            PhotoStore.delete(context, name)
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                // 复制可能读一张几 MB 的原图，必须在 IO 线程。
                val imported = withContext(Dispatchers.IO) { PhotoStore.importFromUri(context, uri) }
                importing = false
                if (imported != null) {
                    if (deleteReplacedImmediately && photoName != null) {
                        deletePhotoFile(photoName)
                    }
                    onPhotoChanged(imported)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "照片（可选）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            PhotoThumbnail(photoName = photoName)

            Column {
                TextButton(
                    onClick = {
                        // 先建好目标文件：系统相机需要一个「已存在且可写」的 URI 才肯写。
                        val target = PhotoStore.newCaptureTarget(context)
                        pendingCaptureName = target.name
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            target,
                        )
                        captureLauncher.launch(uri)
                    },
                    enabled = enabled && !importing,
                ) {
                    Text(text = "📷 拍照")
                }

                TextButton(
                    onClick = {
                        pickLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = enabled && !importing,
                ) {
                    Text(text = "🖼 从相册")
                }

                if (photoName != null) {
                    TextButton(
                        onClick = {
                            // 与「换图」同一分支逻辑：延迟模式下**只清名字**，文件留给保存成功后的调用方删
                            // （此时取消编辑，库里仍指着这个文件，删了就丢图）。
                            if (deleteReplacedImmediately) deletePhotoFile(photoName)
                            onPhotoChanged(null)
                        },
                        enabled = enabled && !importing,
                    ) {
                        Text(text = "移除照片")
                    }
                }
            }
        }
    }
}

/**
 * 照片缩略图（80dp 方块；没有照片时给一个中性占位，避免布局在选图前后跳动）。
 *
 * 解码走 [produceState] + `Dispatchers.IO`：解码是阻塞 IO，直接在组合里做会卡首帧。
 */
@Composable
private fun PhotoThumbnail(photoName: String?) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = photoName) {
        value = if (photoName == null) {
            null
        } else {
            withContext(Dispatchers.IO) { PhotoStore.decode(context, photoName, THUMBNAIL_PX) }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(80.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "物品照片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(80.dp),
            )
        } else {
            Text(
                text = "无",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 缩略图解码的目标边长（80dp 的方块，按 2x/3x 屏给足余量）。 */
private const val THUMBNAIL_PX = 240
