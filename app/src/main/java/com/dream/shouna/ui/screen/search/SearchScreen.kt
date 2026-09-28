package com.dream.shouna.ui.screen.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.ui.component.EmptyState
import com.dream.shouna.ui.component.ItemRow
import com.dream.shouna.ui.component.ShounaSearchBar
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.goItemDetail
import com.dream.shouna.ui.navigation.popBack

/**
 * 有状态包装层（ARCHITECTURE §2）：取 ViewModel、把 UiState 下传；导航动作在此组装。
 */
@Composable
fun SearchRoute(initialQuery: String?) {
    val viewModel: SearchViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    LaunchedEffect(initialQuery) {
        viewModel.setInitialQuery(initialQuery)
    }

    SearchScreen(
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onResultClick = { itemId -> navController.goItemDetail(itemId) },
        onBack = { navController.popBack() },
    )
}

/**
 * 无状态页：输入即搜；结果行 = 名称 + 关键词高亮（无路径、无分类后缀、无时间）。
 */
@Composable
fun SearchScreen(
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onResultClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            ShounaSearchBar(
                value = uiState.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) {
                Text(text = "返回")
            }
        }

        when {
            // 未输入时不展示空态文案，保持安静。
            uiState.query.isBlank() -> Unit
            uiState.results.isEmpty() -> EmptyState(text = "没有找到相关物品")
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = uiState.results, key = { it.doc.itemId }) { hit ->
                    ItemRow(
                        name = hit.doc.name,
                        highlightRange = hit.highlightRange,
                        onClick = { onResultClick(hit.doc.itemId) },
                    )
                }
            }
        }
    }
}
