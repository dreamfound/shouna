package com.dream.shouna.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dream.shouna.R
import com.dream.shouna.domain.ai.AiItemEdit
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.LocationTreeRow

/**
 * 逐条确认弹窗（`V2-FR-11` 起为拍照通道的结果形态，2026-10-09 起语音通道也用它）。
 *
 * 一轮识别可能出多件，本弹窗**一次只呈现一件**，确认或放弃后弹下一件。三个出口：
 * - **确认**：**立刻弹下一件**，这一件的写库投给后台串行队列 —— 弹窗不为写库停一帧
 *   （`ui/ai/AiItemQueue.confirm`）；
 * - **放弃**：跳过这一件，继续弹下一件（不是放弃整轮）；
 * - **关闭**（右上角文字按钮 / 点外部 / 系统返回键）：放弃**本次录入**，剩下未处理的一并丢掉。
 *
 * 【两种形态，同一个窗口】`text` 槽有两个互斥形态，切换**不新开窗口**（`实现约束.md` §4-4：
 * 录入期间不得弹出位置 / 分类对话框）：
 * 1. **表单态**：名称 / 位置行 / 分类 chips / 备注；
 * 2. **位置选择态**：点位置行进入，[LocationTreeList] 就地铺开位置树，选中即回表单态。
 *
 * 【两种形态**各有一套**按钮，不共用】表单态 = 「确认」+「放弃」+ 右上角「关闭」；位置选择态 =
 * 只留一个「取消」（退回表单），「确认 / 放弃」与右上角「关闭」**都隐去** —— 那两个动作属于**整件**，
 * 拿来结束「挑位置」会在语义上误导（用户 2026-10-10：「没有取消，共用一个按钮，这不对」）。
 * 该屏的出口有三个且**同义**：「取消」按钮 / 返回键 / 点外部（见 `onDismissRequest`），一律退回
 * 表单，**不会**丢掉整轮录入。位置行选中即自动回表单，故无需「确定」。
 *
 * 【位置可不可改由 [locationEditable] 决定，两页的差别就在这一项】
 * - **拍照通道**（`false`）：位置在识别之前就定好了（`V2-FR-13`「位置先定后用」），模型这一路
 *   也永远不返回位置。想换位置就关掉整轮、回页面重选再识别 —— 在弹窗里再开一个位置选择窗口
 *   就成了弹窗套弹窗。
 * - **语音通道**（`true`）：位置是**模型从那句话里猜的**，猜错（或说的层库里没有、已就近降级）
 *   必须能就地纠。用户 2026-10-09 口径：「语音时，可以对位置进行修改」。
 *   **没提到位置的那一件也能因此救回来** —— 补一个位置，「确认」随即解禁。
 *
 * 【为什么分类用行内 chips 而不是第二个对话框】同上（另见 §4-4）。`CategoryChips` 是页面里的
 * 一排 chip，零新增窗口。
 *
 * 【为什么三个出口都压在一个 `AlertDialog` 上】点外部与返回键在 Compose 里只能落到
 * `onDismissRequest` 一处，把它定义为「关闭」后，三个入口的语义就自动统一了。
 *
 * 【切件为什么不会闪】窗口的存续只取决于队列是否为空（页面用 `if (editing != null)` 包住它），
 * 而「下一件」只是把 `index` 加一 ⇒ 该调用点在组合树里的位置不变 ⇒ `AlertDialog` 内部
 * 那个 Dialog 实例被复用，切件只是**重组内容**，不会 dismiss 再 show。
 *
 * 【「窗口不重建」不等于「一切沿用」：内容子树的瞬时状态一律按件重建】上一段说的是 **Dialog
 * 窗口**。`text` 槽里的东西是另一回事 —— 用户在第 1 件上留下的是**这一件自己的瞬时状态**：
 * 滚动位置、输入焦点与光标、随之打开的软键盘、分类 chips 那一排 `LazyRow` 的横向滚动，
 * 还有「正在挑位置」这个形态本身。若它们落在同一个组合位置上就会**整批**带到第 2 件
 * （第 2 件一打开停在备注栏、键盘还开着、chips 还停在右侧、甚至直接停在位置树上）。
 * 故 `text` 槽整体**以件为 key**（`key(number)`），`picking` 也按件 remember：切件时重建的是
 * 这棵内容子树，不是窗口。
 *
 * 两件事不冲突：`Dialog` 实例由 `AlertDialog` 自己持有，`key` 只作用在它内部的槽位上。
 * 也正因为外面有这层 key，槽内的 `rememberScrollState()` 不必自带 key —— 子树重建时它从 0 起。
 * **不要在这层 key 之外再放「跨件要保住」的 `remember`**：那会让它随件一起丢
 * （`picking` 正是「不该跨件保留」的那一类，故它也按件）。
 *
 * 无状态：数据与回调全部由页面下传（`实现约束.md` §3-4）。
 *
 * 被调用方：`ui/screen/voiceai/VoiceAiIntakeScreen`、`ui/screen/photoai/PhotoAiIntakeScreen`
 */
@Composable
fun AiItemDialog(
    item: AiItemEdit,
    number: Int,
    total: Int,
    categories: List<Category>,
    /**
     * 位置是否可改。**语音通道传 `true`、拍照通道传 `false`** —— 两个通道对位置的所有权不同，
     * 见类注释。传 `false` 时位置行退化成一行只读文本，下面两项也就不会被用到。
     */
    locationEditable: Boolean,
    /** 位置选择态的数据源（与页面读的是**同一次** `observeTree()` 快照）。 */
    locationRows: List<LocationTreeRow> = emptyList(),
    onNameChanged: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    /** 位置选中：`id` 与**该 id 对应的权威路径文本**一起回传，两者同源。[locationEditable] 为假时不会被调用。 */
    onLocationSelected: (locationId: String, locationText: String) -> Unit = { _, _ -> },
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    onClose: () -> Unit,
) {
    // 「正在挑位置」是**这一件自己的**瞬时状态 ⇒ 与内容子树一样按件重建（见类注释末段）。
    var picking by remember(number) { mutableStateOf(false) }

    AlertDialog(
        // 返回键 / 点外部：在位置选择态里先退回表单，否则才是「放弃本次录入」。
        onDismissRequest = { if (picking) picking = false else onClose() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ai_item_dialog_title, number, total),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // 关闭是「放弃本次录入」，与「放弃」（只丢这一件）不是一回事，故分成两处：
                // 右上角常驻、底部左侧只管当前这一件。
                //
                // 【位置选择态里隐去】挑位置时不给「关闭」入口：该屏唯一的出口是底部「取消」
                // （退回表单）。否则「点错了位置行」会顺手变成「丢掉整轮录入」（用户 2026-10-10 口径）。
                if (!picking) {
                    TextButton(onClick = onClose) {
                        Text(text = stringResource(R.string.ai_item_action_close))
                    }
                }
            }
        },
        text = {
            // 以「第几件」为 key 重建这棵子树（见类注释末段）：滚动位置、焦点 / 光标、软键盘、
            // 分类 chips 的横向滚动、以及位置选择态本身，全是「这一件自己的瞬时状态」。
            // 重建的只是 `AlertDialog` 的 text 槽 —— 窗口本身照旧复用，不会 dismiss 再 show。
            key(number) {
                if (picking) {
                    LocationPickingPanel(
                        itemName = item.name,
                        rows = locationRows,
                        selectedLocationId = item.locationId,
                        onSelect = { row ->
                            onLocationSelected(row.location.id, row.pathText)
                            picking = false
                        },
                    )
                } else {
                    ItemFormPanel(
                        item = item,
                        categories = categories,
                        locationEditable = locationEditable,
                        onNameChanged = onNameChanged,
                        onNoteChanged = onNoteChanged,
                        onCategorySelected = onCategorySelected,
                        onPickLocation = { picking = true },
                    )
                }
            }
        },
        confirmButton = {
            if (picking) {
                // 位置选择态：唯一的按钮 —— 退回表单（不丢件、不丢整轮）。
                TextButton(onClick = { picking = false }) {
                    Text(text = stringResource(R.string.ai_item_action_cancel))
                }
            } else {
                TextButton(onClick = onConfirm, enabled = item.canConfirm) {
                    Text(text = stringResource(R.string.ai_item_action_confirm))
                }
            }
        },
        dismissButton = {
            // 位置选择态里「放弃这一件」不出现：与「取消」并列会让用户分不清
            //「离开这一屏」与「丢掉这一件」。
            if (!picking) {
                TextButton(onClick = onDiscard) {
                    Text(text = stringResource(R.string.ai_item_action_discard))
                }
            }
        },
    )
}

/**
 * 表单态：名称 / 位置行 / 分类 / 备注。
 *
 * 名称与位置都是必填（`实现约束.md` §4-3）：名称空或位置空时「确认」置灰（判据在
 * [AiItemEdit.canConfirm]）—— 不让用户点了再吃一个静默失败。位置行为空时用错误色，
 * 它是**落不了库**的信号，不能让人漏看。
 */
@Composable
private fun ItemFormPanel(
    item: AiItemEdit,
    categories: List<Category>,
    locationEditable: Boolean,
    onNameChanged: (String) -> Unit,
    onNoteChanged: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onPickLocation: () -> Unit,
) {
    Column(
        modifier = Modifier
            // 名称 / 位置 / 分类 / 备注四块在小屏加键盘时可能超出一屏：给个上限并允许内部滚动，
            // 否则底部按钮会被挤出可视区。
            .heightIn(max = FORM_MAX_HEIGHT_DP.dp)
            // 无需自带 key：外面那层 `key(number)` 已保证子树按件重建，这里天然从 0 起。
            .verticalScroll(rememberScrollState()),
    ) {
        OutlinedTextField(
            value = item.name,
            onValueChange = onNameChanged,
            label = { Text(text = stringResource(R.string.ai_item_field_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        LocationLine(
            item = item,
            editable = locationEditable,
            onPickLocation = onPickLocation,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.ai_item_field_category),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        CategoryChips(
            categories = categories,
            selectedCategoryId = item.categoryId,
            onCategorySelected = onCategorySelected,
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = item.note,
            onValueChange = onNoteChanged,
            label = { Text(text = stringResource(R.string.ai_item_field_note)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 位置行（只读一行，或「一行 + 可点改」）。
 *
 * 三种文案，由 `item` 的**位置归属**决定（派生在 `AiItemEdit` 里算，本组件只挑文案 —— §3-4）：
 * - 有位置：`位置：家 › 客厅 › 抽屉`；
 * - **降级**：`位置：客厅（原说「抽屉」不存在，已就近）` —— 用户得能看出「AI 没听错，是那个
 *   位置库里没有」，否则会以为模型认错了；
 * - **没位置**：`缺位置，点这里选一个`（错误色）—— 落库时会跳过它，必须让人不可能漏看。
 */
@Composable
private fun LocationLine(
    item: AiItemEdit,
    editable: Boolean,
    onPickLocation: () -> Unit,
) {
    val hasLocation = !item.hasNoLocation
    val text = when {
        !hasLocation -> stringResource(R.string.ai_item_location_missing)
        item.locationDowngraded -> stringResource(
            R.string.ai_item_location_nearby,
            item.locationText.orEmpty(),
            item.droppedLocationNames.joinToString(LOCATION_LAYER_SEPARATOR),
        )
        else -> stringResource(R.string.ai_item_location, item.locationText.orEmpty())
    }
    val color = if (hasLocation) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.error
    }

    if (!editable) {
        // 拍照通道：位置在识别前就定好了，只读（`V2-FR-13`）。
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    // 语音通道：整行可点，点开位置选择态。给一个「改」字当可见的提示 —— 只靠「文字可点」
    // 用户看不出来（与录入页位置条「点了才能改」的可见性取向一致）。
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPickLocation)
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.ai_item_location_change),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * 位置选择态：就地在弹窗里铺开位置树。
 *
 * 【为什么能在弹窗里铺树】`实现约束.md` §4-4 禁的是**再开一个位置对话框**（弹窗套弹窗）；
 * 把树画在同一个窗口的 `text` 槽里不产生第二个窗口，也与「分类用行内 chips」同一取向。
 *
 * 【为什么没有「＋ 新建位置」】语音通道的位置口径是「落到最深的**已存在**祖先、不新建」
 * （`V2-AI-PRD.md` §4 `V2-FR-10` 落点补注）。给一个新建入口就等于把那条口径从后门打开，
 * 而且会在这个小窗口里再塞一个输入框。真要新建位置，走录入页或位置浏览。
 */
@Composable
private fun LocationPickingPanel(
    itemName: String,
    rows: List<LocationTreeRow>,
    selectedLocationId: String?,
    onSelect: (LocationTreeRow) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.ai_item_location_pick_title, itemName),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        // 树可能很深：给个上下限，内部自己滚（`LocationTreeList` 本身就是 LazyColumn）。
        LocationTreeList(
            rows = rows,
            selectedLocationId = selectedLocationId,
            onSelect = { id -> rows.firstOrNull { it.location.id == id }?.let(onSelect) },
            modifier = Modifier.heightIn(min = TREE_MIN_HEIGHT_DP.dp, max = TREE_MAX_HEIGHT_DP.dp),
        )
    }
}

/** 表单态的高度上限：小屏 + 键盘时仍要留得下底部按钮。 */
private const val FORM_MAX_HEIGHT_DP = 320

/** 位置树在弹窗里的高度区间。 */
private const val TREE_MIN_HEIGHT_DP = 140
private const val TREE_MAX_HEIGHT_DP = 260

/** 降级说明里拼接被丢弃层名的分隔符（与 `LocationPath.SEPARATOR` 同一个字形）。 */
private const val LOCATION_LAYER_SEPARATOR = " › "
