package com.dream.shouna.ui.screen

import com.dream.shouna.ui.vm.QuickAddUiState
import com.dream.shouna.ui.vm.QuickAddViewModel
import com.dream.shouna.ui.vm.VoiceField
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.R
import com.dream.shouna.domain.ai.AiState
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.Location
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.ui.component.ai.AiStatusSection
import com.dream.shouna.ui.component.search.CategoryChips
import com.dream.shouna.ui.component.location.LocationPickerSheet
import com.dream.shouna.ui.component.item.PhotoInputRow
import com.dream.shouna.ui.component.ai.VoiceInputButton
import com.dream.shouna.ui.component.ai.VoiceInputDialog
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.goItemDetail
import com.dream.shouna.ui.navigation.popBack
import com.dream.shouna.util.AiImageEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 有状态包装层：唯一职责 = `hiltViewModel()` 取 ViewModel 后把 UiState 下传（docs/实现约束.md）。
 *
 * [initialLocationId] = 路由参数（P-BROWSE 的「＋ 往这里记一件」带过来的当前层；首页进来为 null）。
 * 它**只在进页时用一次**，故交给 VM 的 [QuickAddViewModel.initialize]（内部自带防重入守卫）——
 * 与 `ItemEditViewModel.load(itemId)` 同一手法：**参数是路由的，去向由 Route 决定，VM 不读路由**。
 *
 * 位置选择弹层**与本层平级**下发、不经过 [QuickAddScreen]（见 [QuickAddLocationPicker]）：
 * 弹层开合是纯粹的本页 UI 态，不该让录入页 body 参与重组。
 */
@Composable
fun QuickAddRoute(initialLocationId: String?) {
    val viewModel: QuickAddViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val locationTree by viewModel.locationTree.collectAsStateWithLifecycle()
    val recentLocations by viewModel.recentLocations.collectAsStateWithLifecycle()
    // **本层不调 `.value`**（故用 `val` 而非 `by`）：只持有 State 对象，读取落在 [QuickAddLocationPicker]。
    val isPickerVisible = viewModel.isLocationPickerVisible.collectAsStateWithLifecycle()
    // FR-18：同一条规矩 —— 语音弹窗的开关也只在这里**持有**，读取下沉到 [QuickAddVoiceDialog]。
    val voiceField = viewModel.voiceField.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    // 初始位置只定一次：key = 路由参数（配置变更后重跑也会被 VM 的守卫挡掉，位置不会被重置）。
    LaunchedEffect(initialLocationId) {
        viewModel.initialize(initialLocationId)
    }

    // FR-14：「查看已有」→ 跳到查重命中的那条记录（导航只在本层下发）。
    LaunchedEffect(Unit) {
        viewModel.viewSimilarEvents.collect { itemId -> navController.goItemDetail(itemId) }
    }

    QuickAddScreen(
        uiState = uiState,
        categories = categories,
        onNameChange = viewModel::onNameChange,
        onCategorySelected = viewModel::onCategorySelected,
        onNoteChange = viewModel::onNoteChange,
        onSaveAndContinue = viewModel::onSaveAndContinue,
        onViewSimilar = viewModel::onViewSimilar,
        onOpenLocationPicker = viewModel::onOpenLocationPicker,
        onRequestVoice = viewModel::onRequestVoice,
        onPhotoChanged = viewModel::onPhotoChanged,
        onAiParse = viewModel::onAiParse,
        onAiToggle = viewModel::onAiToggle,
        onAiApply = viewModel::onAiApply,
        onAiDismiss = viewModel::onAiDismiss,
        onBack = { navController.popBack() },
    )

    QuickAddLocationPicker(
        isVisible = isPickerVisible,
        rows = locationTree,
        recentLocations = recentLocations,
        selectedLocationId = uiState.selectedLocationId,
        onSelect = viewModel::onLocationSelected,
        onCreateLocation = viewModel::onCreateLocation,
        onDismiss = viewModel::onDismissLocationPicker,
    )

    QuickAddVoiceDialog(
        field = voiceField,
        onResult = viewModel::onVoiceResult,
        onDismiss = viewModel::onDismissVoice,
    )
}

/**
 * 无状态页：**顶部返回** + **位置条（必填）** + 名称（自动聚焦）+ 分类 chips + 折叠备注 + 保存并继续 + 计数器。
 *
 * 与 F1 的唯一界面差别 = 位置条。位置未选时保存按钮禁用并给出原因文案
 * —— 这是「所有物品必须有位置」在 UI 上的唯一表达（docs/PRD.md）。
 *
 * 保存成功后**位置条不清空**（VM 保留位置），所以连续录入只需重复「输名称 → 保存」。
 *
 * 位置树与弹层开关**不在本页参数里** —— 弹层开合不该把整页拖进重组，见 [QuickAddLocationPicker]。
 *
 * 【V2 AI 入口（AI-02）】名称框尾部比 🎤 多一个 `AI` 按钮（开关开着才出现）；按下后卡片在
 * **本页内**渲染，不是弹层（守 `实现约束.md` §4-4「连续录入期间不弹对话框」）。照片通道的
 * base64 **在本层编码**（[AiImageEncoder]）再交给 VM —— 本工程文件 IO 一律留在 Compose 层，
 * VM 不持有 `Context`（见 `QuickAddViewModel` 类注释）。
 */
@Composable
fun QuickAddScreen(
    uiState: QuickAddUiState,
    categories: List<Category>,
    onNameChange: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onNoteChange: (String) -> Unit,
    onSaveAndContinue: () -> Unit,
    onViewSimilar: () -> Unit,
    onOpenLocationPicker: () -> Unit,
    onRequestVoice: (VoiceField) -> Unit,
    onPhotoChanged: (String?) -> Unit,
    onAiParse: (String?) -> Unit,
    onAiToggle: (Int) -> Unit,
    onAiApply: () -> Unit,
    onAiDismiss: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var noteExpanded by rememberSaveable { mutableStateOf(false) }

    val hasLocation = uiState.selectedLocationId != null

    // 首次进入与每次保存成功（sessionCount 变化）后都把焦点送回名称框 → FR-10「保持焦点」。
    LaunchedEffect(uiState.sessionCount) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // 「返回」：回上一页。与系统返回键同义（未保存的输入直接丢弃，本页不做拦截确认）。
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text(text = "返回") }
            Text(text = "记一件", style = MaterialTheme.typography.titleMedium)
        }

        // 位置条：必填项。未选时用错误色提示，点击打开树选择弹层（弹层内可「＋ 新建位置」）。
        OutlinedButton(
            onClick = onOpenLocationPicker,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                // 已选时直接摊开**完整面包屑路径**（`家 › 储物间 › 纸箱-07`），不做「已选」这类占位词：
                // 位置条是录入页唯一能确认「这件记到哪」的地方，层级省不得。路径与 id 由 VM 同一次
                // 写入（见 `QuickAddViewModel.selectLocation`），两者不会只到一个。
                // 不设 maxLines —— 深层路径宁可折一行，也不能把最具体的末级名字截掉。
                text = if (hasLocation) {
                    "位置：${uiState.selectedLocationPath}"
                } else {
                    "选择位置（必填）"
                },
                color = if (hasLocation) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = uiState.inputName,
            onValueChange = onNameChange,
            label = { Text(text = "物品名称") },
            singleLine = true,
            // IME 完成键**只收起键盘，不代替保存**：保存的唯一入口是下方「保存并继续」按钮
            // （键盘弹起时按钮由容器级 IME 避让抬到键盘上方，见 RouteHandler）。
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { keyboard?.hide() },
            ),
            // FR-18：尾部语音按钮（点击开弹窗，长按才录音 —— 见 VoiceInputDialog）。
            // V2 AI：同位置再挂一个 AI 按钮（开关开着才出现）—— §3.4「零新增输入框、零新增弹层」。
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VoiceInputButton(onClick = { onRequestVoice(VoiceField.NAME) })
                    if (uiState.aiEnabled) {
                        AiParseButton(
                            parsing = uiState.aiState is AiState.Parsing,
                            onClick = {
                                val photoName = uiState.photoName
                                if (photoName == null) {
                                    // 文字通道：不需要文件 IO，直接交出去。
                                    onAiParse(null)
                                } else {
                                    // 照片通道：编码（读文件 + 缩放 + JPEG + base64）是阻塞 IO，
                                    // 必须在 IO 线程；编码结果为空串 / null 时由 VM 给「照片读取失败」。
                                    scope.launch {
                                        val base64 = withContext(Dispatchers.IO) {
                                            AiImageEncoder.encodeBase64(context, photoName)
                                        }
                                        onAiParse(base64)
                                    }
                                }
                            },
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )

        // FR-14：刚保存的那件有同名 / 高度相似时给一行轻提示 + 一个「查看」入口。
        // **非阻塞**：不拦保存、不弹框；用户不理会继续录入也完全没问题（P1 §8.1-12）。
        // 一开始输入新名字，本提示即消失（VM 在 onNameChange 里清掉），不会指着一件不相关的东西。
        if (uiState.similarCount > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "已有 ${uiState.similarCount} 件同名或相近的物品",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onViewSimilar) {
                    Text(text = "查看")
                }
            }
        }

        // V2 AI：进度 / 失败提示 / 确认卡，全部落在**本页之内**（不是弹层）。
        AiStatusSection(
            aiState = uiState.aiState,
            onToggle = onAiToggle,
            onApply = onAiApply,
            onDismiss = onAiDismiss,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // FR-12：可跳过，不选不影响保存。
        CategoryChips(
            categories = categories,
            selectedCategoryId = uiState.selectedCategoryId,
            onCategorySelected = onCategorySelected,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // FR-16：照片（可选，不拦保存）。放在分类之后、备注之前 —— 它和备注同属「补充说明」，
        // 但比备注更常用来「认东西」，所以不折叠。
        PhotoInputRow(
            photoName = uiState.photoName,
            onPhotoChanged = onPhotoChanged,
        )

        Spacer(modifier = Modifier.height(4.dp))

        TextButton(onClick = { noteExpanded = !noteExpanded }) {
            Text(text = if (noteExpanded) "收起备注" else "添加备注")
        }
        if (noteExpanded) {
            OutlinedTextField(
                value = uiState.note.orEmpty(),
                onValueChange = onNoteChange,
                label = { Text(text = "备注") },
                minLines = 2,
                // FR-18：备注也能用说的（长备注比长名称更常见）。
                trailingIcon = {
                    VoiceInputButton(onClick = { onRequestVoice(VoiceField.NOTE) })
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                keyboard?.hide()
                onSaveAndContinue()
            },
            enabled = uiState.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "保存并继续")
        }

        // 禁用原因直说，避免用户对着灰按钮猜（位置必填是本次新增的硬约束）。
        if (!hasLocation) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "请先选择或新建一个位置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "本次已记录 ${uiState.sessionCount} 件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 位置选择弹层的**薄宿主**：录入页对 `isPickerVisible` 的唯一读取点。
 *
 * 【为什么单拆这一层】Compose 的失效粒度是「**读了该 state 的那个重启作用域**」。开关若在
 * [QuickAddRoute] 或 [QuickAddScreen] 体内读取，翻一次就会连带重跑整个录入页 body
 * （分类 chips、两个输入框、备注、计数）。**只把参数从 [QuickAddScreen] 拿掉是不够的** ——
 * 各页 Screen 带 `List<…>` 参数且未标注 `@Immutable`，**不可跳过**，失效仍会传到它。
 * 故 [QuickAddRoute] 只持有 `State`、不调 `.value`，把读取压到本层：翻开关只重跑本层与弹层子树。
 *
 * 挂载位置不影响观感：`LocationPickerSheet` 渲染为**独立 Dialog 窗口**，在组合树里的位置不决定层级。
 */
@Composable
private fun QuickAddLocationPicker(
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

/**
 * 语音弹层的**薄宿主**：录入页对 `voiceField` 的唯一读取点（与 [QuickAddLocationPicker] 同一手法）。
 *
 * 【为什么读取必须压到这一层】Compose 的失效粒度是「读了该 state 的那个重启作用域」。
 * 若在 [QuickAddRoute] 或 [QuickAddScreen] 体内读 `voiceField.value`，弹窗每次开合都会连带重跑
 * 整个录入页 body（位置条、名称框、分类 chips、照片行、备注、计数）。下沉之后父层**只写不读**，
 * 开合只重跑本层与弹窗子树。
 *
 * 【为什么弹窗本体不带字段参数】[VoiceInputDialog] 只负责「说出文字」，往哪写由调用方决定 ——
 * 于是它不需要知道本页有几个输入框，字段选择就落在下面这一行 `onResult(current, text)` 里。
 */
@Composable
private fun QuickAddVoiceDialog(
    field: State<VoiceField?>,
    onResult: (VoiceField, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = field.value ?: return
    VoiceInputDialog(
        onResult = { text -> onResult(current, text) },
        onDismiss = onDismiss,
    )
}

/**
 * 名称框尾部的 AI 按钮（`docs/code-map/02-数据与领域.md`：与 🎤 **同位置、同形态**）。
 *
 * 【为什么用文字符号而不是矢量图标】与 [VoiceInputButton] 同一理由：AI 类图标要引
 * `material-icons-extended`，为一个图标加一个依赖不划算；本工程既有界面也是这个路子。
 *
 * 【解析中为什么只是换成「…」而不是转圈】`CircularProgressIndicator` 会带来一个持续的动画帧源
 * —— 而录入页是本工程对首帧成本最敏感的一页（实测新结构冷首帧 400~700ms）。一个静态省略号
 * 既表达了「在跑」，又不引入任何额外帧。
 */
@Composable
private fun AiParseButton(
    parsing: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        // 解析中不可重复触发：第二次点击只会命中缓存 / 再排一次队，对用户毫无意义。
        enabled = !parsing,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = "AI 识别" },
    ) {
        Text(
            text = if (parsing) "…" else "AI",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

// AI 状态区（进度 / 失败 / 确认卡）已移到 `ui/component/ai/AiStatusSection.kt` —— 录入页是当前唯一宿主
// （首页与两条 AI 页自 2026-10-09 起都不再走确认卡）。


// 失败原因 → 文案的映射已移到 `ui/component/AiFailText.kt`（三处 AI 界面共用同一套文案）。

