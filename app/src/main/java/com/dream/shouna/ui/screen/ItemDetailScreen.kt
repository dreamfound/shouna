package com.dream.shouna.ui.screen

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.ui.component.common.RelativeTimeText
import com.dream.shouna.ui.component.item.OverdueMark
import com.dream.shouna.ui.component.location.LocationBreadcrumb
import com.dream.shouna.ui.component.location.LocationPickerSheet
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.goItemEdit
import com.dream.shouna.ui.navigation.popBack
import com.dream.shouna.ui.vm.ItemDetailUiState
import com.dream.shouna.ui.vm.ItemDetailViewModel
import com.dream.shouna.util.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 有状态包装层（docs/实现约束.md）：取 ViewModel、把 UiState 与位置树下传。
 * 「✏ 编辑」与「⋯更多」里的编辑入口都进 P-ITEM-EDIT，导航动作在本层下发。
 */
@Composable
fun ItemDetailRoute(itemId: String) {
    val viewModel: ItemDetailViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val locationTree by viewModel.locationTree.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val context = LocalContext.current
    // 「移除照片」要「问计数 → 清库 → 删文件」串起来，中间隔着挂起调用，故需要一个页面作用域。
    val scope = rememberCoroutineScope()

    LaunchedEffect(itemId) {
        viewModel.load(itemId)
    }

    ItemDetailScreen(
        uiState = uiState,
        locationTree = locationTree,
        onConfirmStillHere = viewModel::onConfirmStillHere,
        onMarkGone = viewModel::onMarkGone,
        onRestore = viewModel::onRestore,
        onMarkToBePutBack = viewModel::onMarkToBePutBack,
        onPutBackToStorage = viewModel::onPutBackToStorage,
        onMoveTo = viewModel::onMoveTo,
        // FR-16：先删文件、再清库里的名字。顺序反过来会先丢掉「旧文件名」这个唯一的线索，
        // 文件就再也找不到、删不掉了。中途失败最多留下一个孤儿文件（无害）。
        //
        // 【V2-FR-11（2026-10-09）：先判引用，再决定删不删文件】拍照 AI 录入产出的多件记录
        // 共用同一个照片文件，删早了会把别人的照片弄成破图。四步顺序固定：
        // **取定名字（局部 val）→ 问 VM 是不是最后一个引用者 → 清库 → 才删文件**。
        // 名字必须先取成局部值：清库后 UiState 随 Flow 重发变 null，就再没有线索了。
        onRemovePhoto = {
            val name = uiState.photoName
            scope.launch {
                // 计数要在清库**之前**问（此时包含自己）：>1 = 还有别的记录在用这个文件。
                val isLastReference = viewModel.isLastPhotoReference()
                viewModel.onRemovePhoto()
                if (name != null && isLastReference) PhotoStore.delete(context, name)
            }
        },
        onEditClick = { navController.goItemEdit(itemId) },
        onBack = { navController.popBack() },
    )
}

/**
 * 详情页：**顶部返回** + 名称 + 照片（点击放大）+ 位置面包屑 + **物品信息（常显，不折叠）** +
 * 是否存在（最后确认 / ⚠ / 状态动作） + 「✏ 编辑」与「⋯更多」。
 *
 * 【2026-10-13 版面重排】按「先看它是什么、再看它在不在」的顺序：
 * ① 上半部 = **物品信息**（分类 / 别名 / 数量 / 备注 / 最后变动）—— P1 起这几项藏在「⋯更多」里，
 *    要展开才能看见；现在**常显**，因为它正是「这是个什么东西」的答案；
 * ② 下半部 = **是否存在**（最后确认行 + FR-27 的 ⚠ + 状态动作）—— 这是「要不要采取动作」的判断
 *    依据，放得更靠下、更靠近拇指。
 * 「⋯更多」**只留操作类**（标记待归位 / 归位、移动到…、编辑分类 / 别名 / 数量 / 备注）
 * —— 这些是「去做一件事」的入口，不是现状，仍收在折叠区里。
 *
 * 按钮区按当前状态切换：常规态给「✓ 还在」与「✕ 不在了」；已标记「不在了」只给「恢复」
 * —— 已失效的物品再点「还在」语义不清（恢复本身就是一次确认）。
 *
 * 字段本身的编辑一律落在 P-ITEM-EDIT（本页只读现状 + 给入口）。
 */
@Composable
fun ItemDetailScreen(
    uiState: ItemDetailUiState,
    locationTree: List<LocationTreeRow>,
    onConfirmStillHere: () -> Unit,
    onMarkGone: () -> Unit,
    onRestore: () -> Unit,
    onMarkToBePutBack: () -> Unit,
    onPutBackToStorage: () -> Unit,
    onMoveTo: (String) -> Unit,
    onRemovePhoto: () -> Unit,
    onEditClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasItem = uiState.item != null
    val actionsEnabled = hasItem && !uiState.isBusy

    // 「⋯更多」开合与「移动到…」弹层都是纯粹的本页 UI 态，不进 ViewModel。
    var moreExpanded by remember { mutableStateOf(false) }
    // 「移动到…」的开关在本页**只写不读**（`movePickerVisible.value = true` 不建立订阅），
    // 读取下沉到 [ItemDetailMovePicker] —— 读在哪，失效就只到哪。
    val movePickerVisible = remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // 「返回」：回上一页。本页无未保存状态，与系统返回键同义，不需要拦截确认。
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text(text = "返回") }
            Text(text = "物品详情", style = MaterialTheme.typography.titleMedium)
        }

        Text(
            text = uiState.item?.name.orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // FR-16：照片（最直接的辨识物，放在位置面包屑之前）。没有照片时整块不渲染 ——
        // 详情页对没拍过照的物品保持 P1 的原样，不凭空多出一块占位。点击图片可放大。
        //
        // 先取到局部变量再判空：`uiState.photoName` 是带自定义 getter 的计算属性
        // （`item?.photoPath`），Kotlin 不对它做智能转换。
        val photoName = uiState.photoName
        if (photoName != null) {
            ItemPhoto(
                photoName = photoName,
                onRemove = onRemovePhoto,
                enabled = actionsEnabled,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // FR-02：位置面包屑（长按复制）。为空时整行不渲染。
        LocationBreadcrumb(pathText = uiState.locationPath)

        Spacer(modifier = Modifier.height(16.dp))

        // ---- 物品信息：常显，不折叠 ------------------------------------------------------
        // 这几项在 P-ITEM-EDIT 里改，这里只让用户不必跳页就能看到。
        HorizontalDivider()
        DetailField(label = "分类", value = uiState.categoryName ?: "未分类")
        DetailField(
            label = "别名",
            value = if (uiState.aliases.isEmpty()) "无" else uiState.aliases.joinToString("、"),
        )
        DetailField(label = "数量", value = uiState.quantity.toString())
        // 备注为空时整行不渲染 —— 没备注的物品不该多出一行「无」。
        uiState.note?.takeIf { it.isNotBlank() }?.let { note ->
            DetailField(label = "备注", value = note)
        }
        if (uiState.relativeModifiedText.isNotEmpty()) {
            DetailField(label = "最后变动", value = uiState.relativeModifiedText)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ---- 是否存在：时间证据 + 状态动作 ------------------------------------------------
        RelativeTimeText(text = uiState.relativeConfirmText)

        // FR-27：超期（含从未确认）在详情页明确标出。
        OverdueMark(
            visible = uiState.isOverdue,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.isGone) {
            Button(
                onClick = onRestore,
                enabled = actionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "恢复为「在存放中」")
            }
        } else {
            Button(
                onClick = onConfirmStillHere,
                enabled = actionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "✓ 还在")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onMarkGone,
                enabled = actionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "✕ 不在了")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            // 「✏」：直达 P-ITEM-EDIT（字段编辑的唯一落点）。
            TextButton(onClick = onEditClick, enabled = hasItem) {
                Text(text = "✏ 编辑")
            }
            // 「⋯更多」：只剩**操作类**入口（现状字段已上移到「物品信息」区）。
            TextButton(onClick = { moreExpanded = !moreExpanded }, enabled = hasItem) {
                Text(text = if (moreExpanded) "收起" else "⋯更多")
            }
        }

        if (moreExpanded && hasItem) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // FR-38：待归位 ↔ 归位（状态变化，刷新「最后变动」；不写「最后确认」）。
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (uiState.isToBePutBack) "待归位中" else "待归位",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (uiState.isToBePutBack) {
                    TextButton(onClick = onPutBackToStorage, enabled = actionsEnabled) {
                        Text(text = "归位")
                    }
                } else {
                    TextButton(onClick = onMarkToBePutBack, enabled = actionsEnabled) {
                        Text(text = "标记待归位")
                    }
                }
            }

            TextButton(onClick = { movePickerVisible.value = true }, enabled = actionsEnabled) {
                Text(text = "移动到…")
            }

            TextButton(onClick = onEditClick, enabled = hasItem) {
                Text(text = "编辑分类 / 别名 / 数量 / 备注")
            }
        }
    }

    ItemDetailMovePicker(
        isVisible = movePickerVisible,
        rows = locationTree,
        // 【选中即关弹层】与其余 6 个位置选择调用点同一口径（录入页 / 编辑页 / 浏览页「迁移物品到…」
        // / 统计页 C-5「归位到…」/ 拍照页 / AI 确认弹窗）。原先这里是**唯一**不关的一处：
        // 物品其实已经换好位置、面包屑也已随位置树刷新，但弹层盖在上面 ⇒ 页面上看不出任何变化，
        // 用户会以为「移动没生效」而反复点。`movePickerVisible` 在本层仍只写不读（写不建立订阅），
        // 失效粒度不受影响，读取照旧只在 [ItemDetailMovePicker]。
        onSelect = { locationId ->
            onMoveTo(locationId)
            movePickerVisible.value = false
        },
        onDismiss = { movePickerVisible.value = false },
    )
}

/**
 * 「移动到…」弹层的**薄宿主**：本页对该开关的唯一读取点。
 *
 * 开关若在 [ItemDetailScreen] 体内读，每次开合都会把整页拖进重组（名称、面包屑、物品信息、
 * 时间与状态区、按钮区）。下沉后父层**只写不读**，开合仅重跑本层与弹层子树。
 *
 * 挂载位置不影响观感：`LocationPickerSheet` 渲染为**独立 Dialog 窗口**，在组合树里的位置不决定层级。
 */
@Composable
private fun ItemDetailMovePicker(
    isVisible: MutableState<Boolean>,
    rows: List<LocationTreeRow>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (isVisible.value) {
        LocationPickerSheet(
            rows = rows,
            recentLocations = emptyList(),
            selectedLocationId = null,
            onSelect = onSelect,
            onCreateLocation = { /* 移动目标从既有位置里挑，不支持此处新建 */ },
            onDismiss = onDismiss,
            allowCreate = false,
        )
    }
}

/** 物品信息区的一行「标签：值」。 */
@Composable
private fun DetailField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(LABEL_WEIGHT),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(VALUE_WEIGHT),
        )
    }
}

/** 物品信息区「标签 / 值」两列宽度比（标签窄一些，长别名、长分类名与长备注都留给值列）。 */
private const val LABEL_WEIGHT = 0.28f
private const val VALUE_WEIGHT = 0.72f

/**
 * FR-16：详情页的照片 + 「移除照片」 + 点击放大。
 *
 * 解码走 [produceState] + `Dispatchers.IO`：`BitmapFactory` 是阻塞调用，放组合里会在首帧
 * 直接卡一次（`gfxutil` 上就是一次掉帧）。`key1 = photoName` 保证换了照片才重新解码。
 *
 * 【点击放大】页内这张是 `ContentScale.Crop` 裁过的 200dp 图（只为「一眼认出」，不为看细节），
 * 想看全图就点它 → [PhotoZoomDialog] 里**按屏宽重新解码**（[ZOOM_PX]）并用 `Fit` 呈现。
 *
 * 移除只在**这里**给一次：录入页可以随意重拍，但存下来之后「删掉照片」是一个破坏性动作，
 * 收在照片自己下面、离它最近，比塞进「⋯更多」更好找。
 */
@Composable
private fun ItemPhoto(
    photoName: String,
    onRemove: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = photoName) {
        value = withContext(Dispatchers.IO) { PhotoStore.decode(context, photoName, DETAIL_PX) }
    }

    // 「点开看大图」是本页唯一的照片手势，开关是纯页面态（不进 ViewModel）。
    var zoomed by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "物品照片（点击放大）",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { zoomed = true },
            )
        }
        TextButton(onClick = onRemove, enabled = enabled) {
            Text(text = "移除照片")
        }
    }

    if (zoomed) {
        PhotoZoomDialog(photoName = photoName, onDismiss = { zoomed = false })
    }
}

/**
 * 全屏看大图（FR-16 的放大形态）。
 *
 * 【为什么另开一个 Dialog 而不是把页内那张图撑大】详情页是整页滚动的 `Column`，页内放大要连带
 * 处理滚动、裁切与返回手势；独立窗口一次 `usePlatformDefaultWidth = false` 就全屏，
 * 关掉即回到原地，页面本体一个字节都不动。
 *
 * 【退出方式两个】点任意空白处、右上角「关闭」—— 大图上没有其它动作，退出口越简单越好。
 * 点空白用 `pointerInput` 而不是 `clickable`：全屏黑底上不需要点击涟漪（会闪一整屏白），
 * 也不必给读屏多加一个「按钮」语义。
 */
@Composable
private fun PhotoZoomDialog(
    photoName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = photoName) {
        value = withContext(Dispatchers.IO) { PhotoStore.decode(context, photoName, ZOOM_PX) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        ) {
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "物品照片（放大）",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Text(text = "关闭", color = Color.White)
            }
        }
    }
}

/** 详情页照片解码的目标边长（约 200dp 高 × 3x 屏）。 */
private const val DETAIL_PX = 720

/** 放大视图的采样目标：按屏宽量级给（低端机内存与观察细节的折中）。 */
private const val ZOOM_PX = 1600
