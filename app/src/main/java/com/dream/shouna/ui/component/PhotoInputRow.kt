package com.dream.shouna.ui.component

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
 * 被调用方：`ui/screen/add/QuickAddScreen.kt`。
 */
@Composable
fun PhotoInputRow(
    photoName: String?,
    onPhotoChanged: (String?) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 交给系统相机之前先记下目标文件名：回调只告诉「成没成」，不说写到哪。
    var pendingCaptureName by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    val captureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { saved ->
        val name = pendingCaptureName
        pendingCaptureName = null
        if (saved && name != null) {
            if (photoName != null && photoName != name) PhotoStore.delete(context, photoName)
            onPhotoChanged(name)
        } else {
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
                    if (photoName != null) PhotoStore.delete(context, photoName)
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
                            PhotoStore.delete(context, photoName)
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
