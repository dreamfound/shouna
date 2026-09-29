package com.dream.shouna.ui.screen.itemdetail

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.ui.component.LocationBreadcrumb
import com.dream.shouna.ui.component.OverdueMark
import com.dream.shouna.ui.component.RelativeTimeText

/**
 * 有状态包装层（ARCHITECTURE §2）：取 ViewModel、把 UiState 下传。
 */
@Composable
fun ItemDetailRoute(itemId: String) {
    val viewModel: ItemDetailViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(itemId) {
        viewModel.load(itemId)
    }

    ItemDetailScreen(
        uiState = uiState,
        onConfirmStillHere = viewModel::onConfirmStillHere,
        onMarkGone = viewModel::onMarkGone,
        onRestore = viewModel::onRestore,
    )
}

/**
 * 详情页：名称 + 位置面包屑 + 两行时间（最后确认 / 最后变动）+ 超期标记 + 状态动作。
 *
 * 按钮区按当前状态切换：常规态给「✓ 还在」与「✕ 不在了」；已标记「不在了」只给「恢复」
 * —— 已失效的物品再点「还在」语义不清（恢复本身就是一次确认）。
 */
@Composable
fun ItemDetailScreen(
    uiState: ItemDetailUiState,
    onConfirmStillHere: () -> Unit,
    onMarkGone: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasItem = uiState.item != null
    val actionsEnabled = hasItem && !uiState.isBusy

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = uiState.item?.name.orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

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
    }
}
