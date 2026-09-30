package com.dream.shouna.ui.screen.itemedit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.domain.model.Category
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.popBack

/**
 * 有状态包装层（ARCHITECTURE §2）：取 ViewModel、把 UiState 与分类列表下传。
 */
@Composable
fun ItemEditRoute(itemId: String) {
    val viewModel: ItemEditViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    LaunchedEffect(itemId) {
        viewModel.load(itemId)
    }

    ItemEditScreen(
        uiState = uiState,
        categories = categories,
        onBack = { navController.popBack() },
    )
}

/**
 * P-ITEM-EDIT 物品编辑页（别名 / 数量 / FR-14 跳转落点）—— **骨架**。
 *
 * 当前渲染：标题 + 物品名 + 「待实现」清单 + 返回。**不挂载** [com.dream.shouna.ui.component.AliasEditor]
 * 等交互组件 —— 骨架期的可测边界是「路由可达」，可点但会走到 TODO 桩的控件一律不挂
 * （避免出现「点了没反应」或「点了崩」两种都说不清的中间态）。
 *
 * 未渲染（P1-04 实现期补）：分类 chips（复用 P-ADD 的 `CategoryChips`）、别名 chips 编辑器、
 * 数量输入、备注/去向备注、保存按钮、FR-14 的「查看已有」入口。
 */
@Composable
fun ItemEditScreen(
    uiState: ItemEditUiState,
    categories: List<Category>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "编辑物品",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = uiState.name.ifEmpty { "（未载入）" },
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "待实现（P1-04）：分类（可选 ${categories.size} 项）· " +
                "别名（${uiState.aliases.size}/${ItemEditViewModel.MAX_ALIAS_COUNT}）· " +
                "数量 ${uiState.quantityInput} · 备注",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onBack) {
            Text(text = "返回")
        }
    }
}
