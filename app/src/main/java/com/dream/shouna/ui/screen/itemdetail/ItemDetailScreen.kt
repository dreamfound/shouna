package com.dream.shouna.ui.screen.itemdetail

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.ui.component.LocationBreadcrumb
import com.dream.shouna.ui.component.LocationPickerSheet
import com.dream.shouna.ui.component.OverdueMark
import com.dream.shouna.ui.component.RelativeTimeText
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.goItemEdit
import com.dream.shouna.util.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 有状态包装层（ARCHITECTURE §2）：取 ViewModel、把 UiState 与位置树下传。
 * 「✏ 编辑」与折叠区内的编辑入口都进 P-ITEM-EDIT，导航动作在本层下发。
 */
@Composable
fun ItemDetailRoute(itemId: String) {
    val viewModel: ItemDetailViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val locationTree by viewModel.locationTree.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val context = LocalContext.current

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
        onRemovePhoto = {
            PhotoStore.delete(context, uiState.photoName)
            viewModel.onRemovePhoto()
        },
        onEditClick = { navController.goItemEdit(itemId) },
    )
}

/**
 * 详情页：名称 + 位置面包屑 + 两行时间（最后确认 / 最后变动）+ 超期标记 + 状态动作 + 「⋯更多」折叠区。
 *
 * 按钮区按当前状态切换：常规态给「✓ 还在」与「✕ 不在了」；已标记「不在了」只给「恢复」
 * —— 已失效的物品再点「还在」语义不清（恢复本身就是一次确认）。
 *
 * **P1 新增「⋯更多」折叠区**（P1 §8.1-17 / §4 P1-04 ⑥）：折叠区是**入口**，不是编辑表单 ——
 * 里面只读列出现状（分类 / 别名 / 数量 / 最后变动）并给出三个动作入口：待归位（或归位）、
 * 移动到…、以及进入 P-ITEM-EDIT 的编辑入口。字段本身的编辑一律落在 P-ITEM-EDIT。
 *
 * 【为什么折叠】详情页默认只看「东西在哪、还在不在」，这几个字段是次要信息；
 * 展开后纵向变长是折叠的代价（P1 §8.1-17 的止损行）。
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
    modifier: Modifier = Modifier,
) {
    val hasItem = uiState.item != null
    val actionsEnabled = hasItem && !uiState.isBusy

    // 折叠区开合与「移动到…」弹层都是纯粹的本页 UI 态，不进 ViewModel。
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
        Text(
            text = uiState.item?.name.orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // FR-16：照片（最直接的辨识物，放在位置面包屑之前）。没有照片时整块不渲染 ——
        // 详情页对没拍过照的物品保持 P1 的原样，不凭空多出一块占位。
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

        Spacer(modifier = Modifier.height(8.dp))

        RelativeTimeText(text = uiState.relativeConfirmText)

        if (uiState.relativeModifiedText.isNotEmpty()) {
            RelativeTimeText(text = "最后变动：${uiState.relativeModifiedText}")
        }

        // FR-27：超期（含从未确认）在详情页明确标出。
        OverdueMark(
            visible = uiState.isOverdue,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

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
            // 「⋯更多」：展开折叠区（入口集合），不是直接跳页。
            TextButton(onClick = { moreExpanded = !moreExpanded }, enabled = hasItem) {
                Text(text = if (moreExpanded) "收起" else "⋯更多")
            }
        }

        if (moreExpanded && hasItem) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 只读现状：这几项在 P-ITEM-EDIT 里改，这里只让用户不必跳页就能看到。
            DetailField(label = "分类", value = uiState.categoryName ?: "未分类")
            DetailField(
                label = "别名",
                value = if (uiState.aliases.isEmpty()) "无" else uiState.aliases.joinToString("、"),
            )
            DetailField(label = "数量", value = uiState.quantity.toString())
            if (uiState.relativeModifiedText.isNotEmpty()) {
                DetailField(label = "最后变动", value = uiState.relativeModifiedText)
            }

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
        onSelect = onMoveTo,
        onDismiss = { movePickerVisible.value = false },
    )
}

/**
 * 「移动到…」弹层的**薄宿主**：本页对该开关的唯一读取点。
 *
 * 开关若在 [ItemDetailScreen] 体内读，每次开合都会把整页拖进重组（名称、面包屑、两行时间、
 * 按钮区、折叠区）。下沉后父层**只写不读**，开合仅重跑本层与弹层子树。
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

/** 折叠区的一行「标签：值」。 */
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

/** 折叠区「标签 / 值」两列宽度比（标签窄一些，长别名与长分类名都留给值列）。 */
private const val LABEL_WEIGHT = 0.28f
private const val VALUE_WEIGHT = 0.72f

/**
 * FR-16：详情页的照片 + 「移除照片」。
 *
 * 解码走 [produceState] + `Dispatchers.IO`：`BitmapFactory` 是阻塞调用，放组合里会在首帧
 * 直接卡一次（`gfxutil` 上就是一次掉帧）。`key1 = photoName` 保证换了照片才重新解码。
 *
 * 移除只在**这里**给一次：录入页可以随意重拍，但存下来之后「删掉照片」是一个破坏性动作，
 * 收在照片自己下面、离它最近，比塞进「⋯更多」折叠区更好找。
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

    Column(modifier = modifier.fillMaxWidth()) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "物品照片",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
        TextButton(onClick = onRemove, enabled = enabled) {
            Text(text = "移除照片")
        }
    }
}

/** 详情页照片解码的目标边长（约 200dp 高 × 3x 屏）。 */
private const val DETAIL_PX = 720
