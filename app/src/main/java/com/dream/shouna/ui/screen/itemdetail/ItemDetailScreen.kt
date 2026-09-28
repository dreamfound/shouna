package com.dream.shouna.ui.screen.itemdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    )
}

/**
 * 无状态页：名称 + 一行「最后确认：N 个月前 / 从未确认」+「✓ 还在」。
 */
@Composable
fun ItemDetailScreen(
    uiState: ItemDetailUiState,
    onConfirmStillHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

        RelativeTimeText(text = uiState.relativeConfirmText)

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onConfirmStillHere,
            // 调用进行中禁用，避免重复点击。
            enabled = uiState.item != null && !uiState.isConfirming,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "✓ 还在")
        }
    }
}
