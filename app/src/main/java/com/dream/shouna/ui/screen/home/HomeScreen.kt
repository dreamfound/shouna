package com.dream.shouna.ui.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.ui.component.EmptyState
import com.dream.shouna.ui.component.ShounaSearchBar
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.goLocationBrowse
import com.dream.shouna.ui.navigation.goQuickAdd
import com.dream.shouna.ui.navigation.goSearch

/**
 * 有状态包装层（ARCHITECTURE §2）。
 */
@Composable
fun HomeRoute() {
    val viewModel: HomeViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    HomeScreen(
        uiState = uiState,
        onSearchClick = { navController.goSearch() },
        onQuickAddClick = { navController.goQuickAdd() },
        onBrowseLocationsClick = { navController.goLocationBrowse() },
    )
}

/**
 * 无状态页：搜索框（点击进搜索页并聚焦）+「＋ 记一件」+「更多」折叠区。
 *
 * 「更多」是 F2 入口的容器（ARCHITECTURE-P0 §0）：本页只放**位置浏览**一项。
 * **不做阈值门控** —— 首启已预置 6 个位置，「位置总数 ≥ 3 才显示」恒真，门控是死代码。
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onSearchClick: () -> Unit,
    onQuickAddClick: () -> Unit,
    onBrowseLocationsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var moreExpanded by rememberSaveable { mutableStateOf(false) }

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

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = { moreExpanded = !moreExpanded }) {
            Text(text = if (moreExpanded) "收起" else "更多")
        }

        if (moreExpanded) {
            TextButton(onClick = onBrowseLocationsClick) {
                Text(text = "位置浏览")
            }
        }

        if (uiState.isEmpty) {
            EmptyState(text = "还没有记录，点「＋ 记一件」开始")
        }
    }
}
