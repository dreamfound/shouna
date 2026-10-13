package com.dream.shouna.ui.component.item

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dream.shouna.util.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FR-16：列表行的小方图（搜索页 / 位置浏览页共用）。
 *
 * 【为什么单独一件】解码是阻塞调用（`BitmapFactory`），三处列表是 `LazyColumn`，每次行进入
 * 可视区都会重新组合 ⇒ 解码必须 ① 出主线程（[produceState] + `Dispatchers.IO`，与详情页
 * `ItemPhoto` 同一手法）、② 带采样（[THUMBNAIL_PX]，手机原图 4000px 直接解会把低端机撑爆）、
 * ③ 命中进程内缓存（[ThumbnailCache]，否则快速滚动会反复解同一张图）。
 *
 * 【解码不出来就不画】文件缺失 / 不是图片 → 整块不渲染（行高退回纯文字行），不显示破图也不崩。
 * 调用方按 `photoName != null` 决定传不传，所以「没照片的物品」连解码都不会发起。
 *
 * 【可发现性】缩略图本身不可点（点击归整行，进详情页）；`contentDescription = null` 是刻意的
 * —— 名称就在同一行，给图再念一遍「物品照片」对读屏用户是纯噪音。
 *
 * 被调用方：`ItemRow`（列表行首）。
 */
@Composable
fun PhotoThumbnail(
    photoName: String,
    modifier: Modifier = Modifier,
    size: Dp = THUMBNAIL_SIZE,
) {
    val context = LocalContext.current
    // 初值先取缓存：命中时首帧就是图，不闪一下空白。key1 = 文件名 ⇒ 换图才重新解码。
    val bitmap by produceState<Bitmap?>(initialValue = ThumbnailCache.get(photoName), key1 = photoName) {
        val cached = ThumbnailCache.get(photoName)
        if (cached != null) {
            value = cached
        } else {
            val decoded = withContext(Dispatchers.IO) { PhotoStore.decode(context, photoName, THUMBNAIL_PX) }
            if (decoded != null) ThumbnailCache.put(photoName, decoded)
            value = decoded
        }
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(THUMBNAIL_CORNER)),
        )
    }
}

/**
 * 缩略图的进程内缓存（**不落盘**，随进程消失）。
 *
 * 【为什么不复用台面上的理由】列表滚动时同一行会被反复组合；一张 144px 的图解码约几十毫秒，
 * 不加缓存就是「滚回去又一卡」。键 = 照片**文件名**（`PhotoStore` 用 UUID 生成），换图即换名，
 * 因此不存在「文件改了、缓存还是旧的」这种失效问题。
 *
 * `LruCache` 自带同步，跨线程读（组合线程）写（IO 线程）安全；按**条目数**限容，
 * 单张约 144×144×4B ≈ 80KB，[CACHE_ENTRIES] 张上限约 5MB，对低端机是可控的代价。
 */
private object ThumbnailCache {
    private val cache = LruCache<String, Bitmap>(CACHE_ENTRIES)

    fun get(photoName: String): Bitmap? = cache.get(photoName)

    fun put(photoName: String, bitmap: Bitmap) {
        cache.put(photoName, bitmap)
    }
}

/** 缩略图边长（列表行里的方图；48dp 也是 NFR-15 的最小触控目标尺寸，视觉上够大够看）。 */
private val THUMBNAIL_SIZE = 48.dp

private val THUMBNAIL_CORNER = 8.dp

/** 解码目标边长：48dp × 3x 屏 ≈ 144px，够清晰又不浪费内存。 */
private const val THUMBNAIL_PX = 144

private const val CACHE_ENTRIES = 64
