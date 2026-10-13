package com.dream.shouna.ui.screen.ai

import com.dream.shouna.ui.vm.PhotoAiIntakeUiState
import com.dream.shouna.ui.vm.PhotoAiIntakeViewModel
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.R
import com.dream.shouna.domain.ai.AiState
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.Location
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.ui.component.ai.AiQueueState
import com.dream.shouna.ui.component.ai.AiItemDialog
import com.dream.shouna.ui.component.ai.text
import com.dream.shouna.ui.component.location.LocationPickerSheet
import com.dream.shouna.ui.component.item.PhotoInputRow
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.popBack
import com.dream.shouna.util.AiImageEncoder
import com.dream.shouna.util.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 有状态包装层（docs/实现约束.md）：唯一职责 = `hiltViewModel()` 取 ViewModel 后把 UiState 下传。
 *
 * 【离开本页要清掉什么】未被落库的那张临时照片**唯一的清理时机就是「离开本页」** —— 页面一离开，
 * 那张图就再没有归属，留着只会变成私有目录里的孤儿。两条离开路径（页内「返回」按钮与系统返回键）
 * 都走 [PhotoAiIntakeScreen] 的 `onBack`，最终落到下面这一个 `onBack` 上，语义只有一份。
 *
 * 【删之前必须过 `PhotoAiIntakeViewModel.canDeletePhotoFile` 那道闸】（用户 2026-10-09 口径：
 * 只删绑定，库中零引用才删文件）。三种情况一张都不删：
 * - 某一件已落库成功 ⇒ `photoName` 早被清空，这里拿到的是 `null`（没图可删，直接回）；
 * - **本轮还有在途落库**（`pendingCommits > 0`）⇒ 那些任务引用的正是这张图，`photo_path`
 *   还没进库、引用计数恒为 0，此刻删就是把后台正在用的文件删掉；
 * - 库里已有记录指着它（多条记录共用一个文件名）。
 * 顺序反过来（先删后清、或压根不判就删）就会把库里记录的图删掉 —— 这一处是本页最容易被改错的地方。
 * 两个判据都收在 VM 的那个方法里（第二道闸读的是结果段的 `pendingCommits`），页面**不再自己读它**。
 *
 * 【为什么不用 `onDispose` 删】旋转 / 进程内重组同样会触发 `onDispose`，那时照片还没落库、却还是
 * 「在用」的，删掉就成了随机丢图（同一取舍记在 `PhotoInputRow` 的类注释里）。故只挂在**用户的显式
 * 离开动作**上。
 *
 * 位置选择弹层与本层平级下发、不经过 [PhotoAiIntakeScreen]（见 [PhotoAiLocationPickerHost]）。
 */
@Composable
fun PhotoAiIntakeRoute() {
    val viewModel: PhotoAiIntakeViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 结果段（队列 / 逐条进度 / 汇总）在独立流上：它与语音 AI 录入页共用同一份实现
    // （`ui/ai/AiItemQueue`），不在本页的 UiState 里。
    val queueState by viewModel.queueState.collectAsStateWithLifecycle()
    val locationTree by viewModel.locationTree.collectAsStateWithLifecycle()
    val recentLocations by viewModel.recentLocations.collectAsStateWithLifecycle()
    // 弹窗内「分类」chips 的名单（FR-12：只从既有分类里选）。与位置树同一手法，单独一条流下发。
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    // **本层不调 `.value`**（故用 `val` 而非 `by`）：只持有 State 对象，读取落在 [PhotoAiLocationPickerHost]。
    val isPickerVisible = viewModel.isLocationPickerVisible.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 判据**记住实例**：`viewModel::canDeletePhotoFile` 每次重组都是一个新的函数引用，下传给
    // 无状态页会让它的相等性判断白算一次（那个 lambda 与 VM 绑定，不该参与重组判定）。
    val canDeletePhoto = remember(viewModel) { viewModel::canDeletePhotoFile }

    PhotoAiIntakeScreen(
        uiState = uiState,
        queue = queueState,
        categories = categories,
        canDeletePhoto = canDeletePhoto,
        onBack = {
            // 用户 2026-10-09 口径：**只删绑定，库中零引用才删文件**；还有在途落库时一律不删
            // （那些任务引用的正是这张图，计数此刻恒为 0、不可信 —— 见 `canDeletePhotoFile`）。
            // **先判后 pop**：`popBack()` 会让本页离开组合、连带取消这个协程，顺序反过来等于不删。
            val photoName = uiState.photoName
            if (photoName == null) {
                navController.popBack()
            } else {
                scope.launch {
                    try {
                        if (viewModel.canDeletePhotoFile(photoName)) {
                            PhotoStore.delete(context, photoName)
                        }
                    } finally {
                        // 判据出错（DB 异常）也不能把用户困在这一页。
                        navController.popBack()
                    }
                }
            }
        },
        onPickLocation = viewModel::onOpenLocationPicker,
        onPhotoChanged = viewModel::onPhotoChanged,
        onRecognize = viewModel::onRecognizePhoto,
        onItemNameChanged = viewModel::onItemNameChanged,
        onItemNoteChanged = viewModel::onItemNoteChanged,
        onItemCategorySelected = viewModel::onItemCategorySelected,
        onItemConfirm = viewModel::onConfirmItem,
        onItemDiscard = viewModel::onDiscardItem,
        onCloseIntake = viewModel::onCloseIntake,
    )

    PhotoAiLocationPickerHost(
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
 * 无状态页：**位置（必填）→ 照片 → 识别**，三段顺序即 `V2-FR-13`「位置先定后用」的版面表达 ——
 * 模型这一路永不返回位置，所以没有位置就没有落点，「识别」在该状态下不可点并给出红字原因。
 *
 * **识别结果的落点在本页之外**：结果不是页内的确认卡，而是逐条弹窗（[AiItemDialog]，由
 * [AiQueueState.editing] 驱动、`AlertDialog` 自成独立窗口）。用户在弹窗里每点一次「确认」，
 * 写库都在**后台串行队列**里跑、本页立刻呈现下一件（见 `ui/ai/AiItemQueue`）——
 * 所以本页的重组不跟着写库走。
 *
 * 【弹窗的位置是只读的】本页传 `locationEditable = false`：位置在识别前就定了（`V2-FR-13`），
 * 想换只能「关闭」整轮、回页面上重选再识别。语音 AI 录入页（P-VOICE-AI）传 `true` ——
 * 那边的位置是模型从那句话里猜的，必须能就地纠。
 *
 * 本页页内只剩两种 AI 状态 —— **识别中一行 / 失败一行**，失败只有一条降级路径：**一行行内提示**，
 * 不弹窗、不阻塞（`实现约束.md` §4-1、`V2-FR-07`）。共用组件 `AiStatusSection` 仍服务录入页。
 *
 * 【要删的图有两处，判据只有一份】`onBack` 与照片行的替换 / 移除都会删文件，两者都必须先过
 * [canDeletePhoto]（本页的图可能已被「确认」过一次、后台正在写库）。
 *
 * 【照片的 base64 在本层编码】守本工程「文件 IO 全留在 Compose 层」的分层（VM 不持 `Context`），
 * 与录入页 AI 按钮的处理完全一致：读文件 + 缩放 + JPEG + base64 是阻塞 IO，必须下 IO 线程。
 */
@Composable
fun PhotoAiIntakeScreen(
    uiState: PhotoAiIntakeUiState,
    queue: AiQueueState,
    categories: List<Category>,
    /**
     * 「这个照片文件能不能删」的判据（由 Route 从 VM 带下来）。本页**唯一真正调用它**的地方是
     * `onBack`；照片行那处把它透传给 `PhotoInputRow.canDeleteFile` —— 组件不能反向依赖本页的 VM。
     */
    canDeletePhoto: suspend (String) -> Boolean,
    onBack: () -> Unit,
    onPickLocation: () -> Unit,
    onPhotoChanged: (String?) -> Unit,
    onRecognize: (String?) -> Unit,
    onItemNameChanged: (String) -> Unit,
    onItemNoteChanged: (String) -> Unit,
    onItemCategorySelected: (String?) -> Unit,
    onItemConfirm: () -> Unit,
    onItemDiscard: () -> Unit,
    onCloseIntake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 系统返回键与页内「返回」是**同一个动作**：两条路都带着「删未落库的照片」这一副作用，
    // 分成两处写迟早只改一处（`BackHandler` 只在有自绘返回时才有意义，本页正是这种页）。
    BackHandler { onBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasLocation = uiState.selectedLocationId != null
    val hasPhoto = uiState.photoName != null
    val parsing = uiState.aiState is AiState.Parsing

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = "返回")
        }

        Text(
            text = stringResource(R.string.photo_ai_title),
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 位置条：必选项，排在最前（`V2-FR-13`）。未选时用错误色提示，点击打开树选择弹层
        // （弹层内可「＋ 新建位置」）。已选时直接摊开**完整面包屑路径** —— 这是本页唯一能确认
        // 「这批东西记到哪」的地方，层级省不得（位置与 id 由 VM 同一次写入，两者不会只到一个）。
        OutlinedButton(
            onClick = onPickLocation,
            enabled = !parsing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (hasLocation) {
                    stringResource(R.string.ai_home_photo_location, uiState.selectedLocationPath)
                } else {
                    stringResource(R.string.ai_home_photo_location_empty)
                },
                color = if (hasLocation) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // FR-16 的照片行原样复用：拍照（零权限）/ 从相册 / 预览 / 移除。
        // `deleteReplacedImmediately` 走默认的 true —— 本页的图在落库前是临时介质，换掉就该删旧文件；
        // 落库之后 VM 会清空照片名，替换删除再也碰不到库里那份。
        // 但「已确认、后台还在写库」那一段窗口里照片名尚未清空、引用计数还是 0 ⇒ 必须设闸
        // （`canDeleteFile`），否则这里会把后台正要挂到记录上的那张图删掉。
        PhotoInputRow(
            photoName = uiState.photoName,
            onPhotoChanged = onPhotoChanged,
            enabled = !parsing,
            canDeleteFile = canDeletePhoto,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                val name = uiState.photoName
                if (name != null) {
                    // 编码是阻塞 IO，必须下 IO 线程；编码失败返回 null，由 VM 给「照片读取失败」。
                    scope.launch {
                        val base64 = withContext(Dispatchers.IO) {
                            AiImageEncoder.encodeBase64(context, name)
                        }
                        onRecognize(base64)
                    }
                }
            },
            enabled = hasPhoto && hasLocation && !parsing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.ai_home_photo_recognize))
        }

        // 按钮为什么是灰的，直说 —— 不让用户对着一个点不动的按钮猜（与录入页位置条同一取向）。
        if (!parsing) {
            val hint = when {
                !hasPhoto -> stringResource(R.string.ai_home_photo_need_photo)
                !hasLocation -> stringResource(R.string.ai_entry_fail_need_location)
                else -> null
            }
            if (hint != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // AI 状态区（本页只剩两种形态）：识别中一行 / 失败一行。
        // `AiState.Draft` 在本页**不再产生** —— 结果走逐条弹窗（见 [AiItemDialog]）；
        // 这里仍列出来只是为了穷尽这个密封类型，不画任何东西。
        when (val aiState = uiState.aiState) {
            AiState.Hidden, AiState.Idle, is AiState.Draft -> Unit

            AiState.Parsing -> Text(
                text = stringResource(R.string.ai_entry_parsing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            is AiState.Failed -> Text(
                // 文案映射仍走共用组件里的那一个扩展函数（`AiFailReason.text()`），
                // 与录入页、语音页逐字一致。
                text = aiState.reason.text(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 落库汇总（`V2-FR-11`「没点确认前库中零新增」的另一半：记了几件要说清）。
        // 队列走完 / 关闭之后才看得到它，所以两句都要有：「已记下」说清结果，
        // **「没记下」必须说** —— 弹窗直接消失会让用户以为全都记下了。
        val savedCount = queue.savedCount
        if (savedCount != null) {
            if (savedCount > 0) {
                Text(
                    text = stringResource(R.string.ai_home_saved, savedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (queue.unsavedCount > 0) {
                Text(
                    text = stringResource(R.string.ai_home_unsaved, queue.unsavedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 逐条确认弹窗：一次只呈现一件（`editing` 非空即显示）。
        // 挂载位置不影响观感：`AlertDialog` 渲染为**独立窗口**，在组合树里的位置不决定层级。
        // 弹窗打开时它是模态的 —— 底下的位置条 / 照片行 / 识别按钮都点不到，返回键也先归它。
        val editing = queue.editing
        if (editing != null) {
            AiItemDialog(
                item = editing,
                number = queue.number,
                total = queue.total,
                categories = categories,
                // 位置在识别前已定（`V2-FR-13`）⇒ 弹窗里只读；要换只能关掉整轮回页面重选。
                locationEditable = false,
                onNameChanged = onItemNameChanged,
                onNoteChanged = onItemNoteChanged,
                onCategorySelected = onItemCategorySelected,
                onConfirm = onItemConfirm,
                onDiscard = onItemDiscard,
                onClose = onCloseIntake,
            )
        }
    }
}

/**
 * 位置选择弹层的**薄宿主**：本页对 `isLocationPickerVisible` 的唯一读取点（与录入页
 * `QuickAddLocationPicker` 同一手法）。
 *
 * 【为什么读取必须压到这一层】Compose 的失效粒度是「读了该 state 的那个重启作用域」。开关若在
 * `PhotoAiIntakeRoute` 或 [PhotoAiIntakeScreen] 体内被读，展开 / 收起一次就会连带重跑整页 body
 * （位置条、照片行、识别按钮、状态区）。故 Route 只持有 `State` 对象、不调 `.value`。
 *
 * 挂载位置不影响观感：`LocationPickerSheet` 渲染为**独立 Dialog 窗口**，在组合树里的位置不决定层级。
 *
 * `allowCreate` 保持默认的 true：用户在拍照前才发现「这东西该放的位置还没有」，此刻能当场建一个，
 * 比逼他先退出去别处建完再回来少一趟。
 */
@Composable
private fun PhotoAiLocationPickerHost(
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
