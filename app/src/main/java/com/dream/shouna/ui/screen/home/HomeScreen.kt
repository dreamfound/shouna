package com.dream.shouna.ui.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.R
import com.dream.shouna.domain.ai.AiState
import com.dream.shouna.domain.model.Location
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.ui.component.AiStatusSection
import com.dream.shouna.ui.component.EmptyState
import com.dream.shouna.ui.component.LocationPickerSheet
import com.dream.shouna.ui.component.PhotoAiIntakePanel
import com.dream.shouna.ui.component.ShounaSearchBar
import com.dream.shouna.ui.component.VoiceInputDialog
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.goLocationBrowse
import com.dream.shouna.ui.navigation.goQuickAdd
import com.dream.shouna.ui.navigation.goSearch
import com.dream.shouna.ui.navigation.goSettings
import com.dream.shouna.ui.navigation.goStats
import com.dream.shouna.util.AiImageEncoder
import com.dream.shouna.util.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 有状态包装层（docs/实现约束.md）。
 * P1：「更多」由 1 项扩到 3 项，新增的统计 / 设置两条导航动作在本层下发。
 * V2：语音入口的四个回调（说完了 / 勾选 / 记下 / 忽略）同样只在这里接线；
 * 「拍照AI记」面板与位置弹层也挂在本层（与录入页 `QuickAddRoute` 同一手法）。
 *
 * 【本层只**持有** State，不调 `.value`】面板与位置弹层的开合、位置树的读取全部下沉到
 * [HomePhotoPanelHost] / [HomeLocationPickerHost]。理由见那两个宿主的注释：Compose 的失效粒度是
 * 「读了该 state 的那个重启作用域」，读取一旦留在本层或 [HomeScreen] 体内，翻一次开关就会连带
 * 重跑整页 body。
 */
@Composable
fun HomeRoute() {
    val viewModel: HomeViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    val locationTree by viewModel.locationTree.collectAsStateWithLifecycle()
    val recentLocations by viewModel.recentLocations.collectAsStateWithLifecycle()
    // 两个开合：`val`（不是 `by`）—— 本层刻意不读它们。
    val isPhotoPanelOpen = viewModel.isPhotoPanelOpen.collectAsStateWithLifecycle()
    val isPickerVisible = viewModel.isLocationPickerVisible.collectAsStateWithLifecycle()

    HomeScreen(
        uiState = uiState,
        onSearchClick = { navController.goSearch() },
        onQuickAddClick = { navController.goQuickAdd() },
        onBrowseLocationsClick = { navController.goLocationBrowse() },
        onStatsClick = { navController.goStats() },
        onSettingsClick = { navController.goSettings() },
        onSpokenText = viewModel::onSpokenText,
        onAiToggle = viewModel::onAiToggle,
        onAiApply = viewModel::onAiApply,
        onAiDismiss = viewModel::onAiDismiss,
        onOpenPhotoPanel = viewModel::onOpenPhotoPanel,
    )

    HomePhotoPanelHost(
        isOpen = isPhotoPanelOpen,
        photoName = uiState.photoName,
        locationPath = uiState.selectedLocationPath,
        parsing = uiState.aiState is AiState.Parsing,
        onPickLocation = viewModel::onOpenLocationPicker,
        onPhotoChanged = viewModel::onPhotoChanged,
        onRecognize = viewModel::onRecognizePhoto,
        onClose = viewModel::onClosePhotoPanel,
    )

    HomeLocationPickerHost(
        isVisible = isPickerVisible,
        rows = locationTree,
        recentLocations = recentLocations,
        selectedLocationId = uiState.selectedLocationId,
        onSelect = viewModel::onLocationSelected,
        onCreateLocation = viewModel::onCreateLocation,
        onDismiss = viewModel::onDismissLocationPicker,
    )
}

/**
 * 无状态页：搜索框（点击进搜索页并聚焦）+「＋ 记一件」+「🎤 说一句」+「更多」折叠区 + AI 状态区。
 *
 * 「更多」是 F2 入口的容器（docs/PRD.md）：P1 起由 **1 项 → 3 项**
 * （位置浏览 / 归纳统计 / 设置）。
 * **不做阈值门控** —— 首启已预置 6 个位置，「位置总数 ≥ 3 才显示」恒真，门控是死代码（P0 §8.1-8）。
 *
 * 【V2 的语音入口】`V2-FR-10` 语音句录入落在**首页**：「说一句」→ 系统转文字 →（用户不接触
 * 原始文本）→ AI 拆成多行草稿 → 本页确认卡 → 点确认才落库。一句话里可以含不同位置的不同东西，
 * 也可以含同一位置的多个东西 —— 这是「多行草稿」这一个机制同时覆盖的两种情形。
 *
 * 【V2 的拍照入口】`V2-FR-11` + `V2-FR-13`：「拍照AI记」→ **先选位置**（位置先定后用）→ 拍照 →
 * 送 AI 视觉得到多行草稿 → **本页同一张确认卡** → 点确认才落库，每一件都落到那个选定的位置。
 * 面板本体是 [PhotoAiIntakePanel]，读取 HomeUiState 里的照片与位置三项。
 *
 * 【可见性】两个入口**都只在 AI 总开关开启时出现**。语音本身不联网（走系统识别服务），但「听一句
 * 话、拆出带位置的多个物品」这件事必须过 AI —— 开关关着时它连拆都拆不了，造一个点了没用的
 * 入口比不出现更糟（也与「开关关着时不新增任何界面」这条硬约束一致）。
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onSearchClick: () -> Unit,
    onQuickAddClick: () -> Unit,
    onBrowseLocationsClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSpokenText: (String) -> Unit,
    onAiToggle: (Int) -> Unit,
    onAiApply: () -> Unit,
    onAiDismiss: () -> Unit,
    onOpenPhotoPanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var moreExpanded by rememberSaveable { mutableStateOf(false) }
    // 语音弹窗的开合：纯本页 UI 态。**刻意不用 rememberSaveable** —— 录音本身无法跨配置变更
    // 恢复，把开合状态存下来只会让旋转后弹出一个「什么都没录」的空窗。
    var voiceVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        ShounaSearchBar(
            value = "",
            onValueChange = {},
            readOnly = true,
            onSearchClick = onSearchClick,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onQuickAddClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "＋ 记一件")
        }

        // V2 的两个 AI 入口。开关关着时整块不出现（零新增 UI、零请求）。
        if (uiState.aiEnabled) {
            // V2-FR-10：语音入口。
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { voiceVisible = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.ai_home_voice_entry))
            }

            // V2-FR-11：拍照入口。**位置在面板里先定**（`V2-FR-13`）—— 与语音那条「位置由那句话
            // 决定」正好互补：说不清位置在哪时，拍一张、自己挑位置。
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenPhotoPanel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.ai_home_photo_entry))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = { moreExpanded = !moreExpanded }) {
            Text(text = if (moreExpanded) "收起" else "更多")
        }

        if (moreExpanded) {
            TextButton(onClick = onBrowseLocationsClick) {
                Text(text = "位置浏览")
            }
            TextButton(onClick = onStatsClick) {
                Text(text = "归纳统计")
            }
            TextButton(onClick = onSettingsClick) {
                Text(text = "设置")
            }
        }

        // AI 状态区：识别中一行 / 失败一行 / 确认卡一段。与录入页**同一个组件**，差别只有
        // `canFillBack = false` —— 首页背后没有主表单，1 件也无处回填，只能直接落库。
        AiStatusSection(
            aiState = uiState.aiState,
            onToggle = onAiToggle,
            onApply = onAiApply,
            onDismiss = onAiDismiss,
            canFillBack = false,
        )

        // 落库汇总。**「没记下」必须说** —— 勾了 5 件却只进库 4 件时，卡片直接消失会让用户
        // 以为全都记下了（首页没有「已选位置」兜底，缺位置的行只能跳过）。
        val savedCount = uiState.savedCount
        if (savedCount != null) {
            if (savedCount > 0) {
                Text(
                    text = stringResource(R.string.ai_home_saved, savedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (uiState.unsavedCount > 0) {
                Text(
                    text = stringResource(R.string.ai_home_unsaved, uiState.unsavedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (uiState.isEmpty) {
            EmptyState(text = "还没有记录，点「＋ 记一件」开始")
        }
    }

    // 语音弹窗：与录入页复用同一个组件（长按说话、松手出字、含权限申请与看门狗）。
    // 识别成功即关窗并把文本交给 VM —— **文本不落到任何输入框**（`V2-FR-10`）。
    if (voiceVisible) {
        VoiceInputDialog(
            onResult = { text ->
                voiceVisible = false
                onSpokenText(text)
            },
            onDismiss = { voiceVisible = false },
        )
    }
}

/**
 * 「拍照AI记」面板的**薄宿主**：首页对 `isPhotoPanelOpen` 的唯一读取点（与录入页
 * `QuickAddLocationPicker` / `QuickAddVoiceDialog` 同一手法）。
 *
 * 【为什么读取必须压到这一层】Compose 的失效粒度是「读了该 state 的那个重启作用域」。开关若在
 * [HomeRoute] 或 [HomeScreen] 体内被读，展开 / 收起一次就会连带重跑整个首页 body（搜索框、两个
 * 入口按钮、更多折叠区、AI 状态区、空态）。**只把参数从 [HomeScreen] 拿掉是不够的** ——
 * `HomeUiState` 变化本就会让 body 重跑，故 [HomeRoute] 只持有 `State` 对象、不调 `.value`。
 *
 * 【照片的 base64 在这里编码】守本工程「文件 IO 全留在 Compose 层」的分层（VM 不持 `Context`），
 * 与录入页 AI 按钮的处理完全一致：读文件 + 缩放 + JPEG + base64 是阻塞 IO，必须下 IO 线程。
 *
 * 【「收起」是唯一的删图时机】面板一收起，那张未被落库的照片就再没有归属 —— 它按 `V2-AI-PRD`
 * 的取向本就不该留存，留着只会变成私有目录里的孤儿。**已落库的照片不会走到这里**：落库成功时
 * VM 已把 `photoName` 清空，此处删的是一个 `null`。顺序反过来就会把库里记录的图删掉 ——
 * 这一处是本页最容易被改错的地方。
 */
@Composable
private fun HomePhotoPanelHost(
    isOpen: State<Boolean>,
    photoName: String?,
    locationPath: String?,
    parsing: Boolean,
    onPickLocation: () -> Unit,
    onPhotoChanged: (String?) -> Unit,
    onRecognize: (String?) -> Unit,
    onClose: () -> Unit,
) {
    if (!isOpen.value) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    PhotoAiIntakePanel(
        photoName = photoName,
        locationPath = locationPath,
        parsing = parsing,
        onPickLocation = onPickLocation,
        onPhotoChanged = onPhotoChanged,
        onRecognize = {
            val name = photoName
            if (name != null) {
                scope.launch {
                    val base64 = withContext(Dispatchers.IO) {
                        AiImageEncoder.encodeBase64(context, name)
                    }
                    onRecognize(base64)
                }
            }
        },
        onClose = {
            PhotoStore.delete(context, photoName)
            onClose()
        },
    )
}

/**
 * 位置选择弹层的**薄宿主**：首页对 `isLocationPickerVisible` 的唯一读取点。
 *
 * 与 [HomePhotoPanelHost] 同一理由（见其注释）。挂载位置不影响观感：`LocationPickerSheet` 渲染为
 * **独立 Dialog 窗口**，在组合树里的位置不决定层级 —— 所以它与面板平级挂着也不会被面板挡住。
 *
 * 复用录入页那个组件（`allowCreate` 保持默认的 true）：用户在首页拍完才发现「这东西该放的位置
 * 还没有」，此刻能当场建一个，比逼他先去别处建完再回来少一趟。
 */
@Composable
private fun HomeLocationPickerHost(
    isVisible: State<Boolean>,
    rows: List<LocationTreeRow>,
    recentLocations: List<Location>,
    selectedLocationId: String?,
    onSelect: (String) -> Unit,
    onCreateLocation: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (isVisible.value) {
        LocationPickerSheet(
            rows = rows,
            recentLocations = recentLocations,
            selectedLocationId = selectedLocationId,
            onSelect = onSelect,
            onCreateLocation = onCreateLocation,
            onDismiss = onDismiss,
        )
    }
}
