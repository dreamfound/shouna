package com.dream.shouna.ui.screen.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.ui.component.EmptyState
import com.dream.shouna.ui.component.FilterChips
import com.dream.shouna.ui.component.FilterOption
import com.dream.shouna.ui.component.ItemRow
import com.dream.shouna.ui.component.RecentQueryChips
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
        onCategoryFilterSelected = viewModel::onCategoryFilterSelected,
        onLocationFilterSelected = viewModel::onLocationFilterSelected,
        onStatusFilterSelected = viewModel::onStatusFilterSelected,
        onClearFilters = viewModel::onClearFilters,
        onResultClick = { itemId -> navController.goItemDetail(itemId) },
        onBack = { navController.popBack() },
    )
}

/**
 * 无状态页：输入即搜 + FR-22 三维筛选。
 *
 * 四段式（对应 [SearchUiState] 的四种形态）：
 * 1. **未输入** → 最近搜索词（FR-23）；没有历史则整块不渲染，保持安静；
 * 2. **有输入但无结果** → 空态文案（筛选生效时补一句「可清空筛选」，不然用户会以为库里真没有）；
 * 3. **有结果** → 结果行 = 名称 + 关键词高亮 + 副标题（位置路径 · 最后确认）+ FR-27 的 ⚠；
 * 4. **筛选行**（FR-22）在有输入时出现：分类 / 位置 / 状态各一排，可叠加、可清空。
 *
 * 副标题与 ⚠ 的判定都在 VM 算好（Screen 不持有 `TimeUtil` / `ConfigRepository`）；
 * 筛选候选项也由 VM 派生，本页只把数据映射成 [FilterOption]（组件参数）。
 */
@Composable
fun SearchScreen(
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onCategoryFilterSelected: (String?) -> Unit,
    onLocationFilterSelected: (String?) -> Unit,
    onStatusFilterSelected: (ItemStatus?) -> Unit,
    onClearFilters: () -> Unit,
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
            // 未输入时展示最近搜索词；点 chip = 回填并立刻检索。
            uiState.query.isBlank() -> RecentQueryChips(
                queries = uiState.recentQueries,
                onQueryClick = onQueryChange,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            else -> {
                // FR-22：筛选行只在有输入时出现 —— 没输入就没有结果集可收窄，摆出来只是噪音。
                FilterSection(
                    uiState = uiState,
                    onCategoryFilterSelected = onCategoryFilterSelected,
                    onLocationFilterSelected = onLocationFilterSelected,
                    onStatusFilterSelected = onStatusFilterSelected,
                    onClearFilters = onClearFilters,
                )

                if (uiState.results.isEmpty()) {
                    EmptyState(
                        text = if (uiState.filters.isEmpty) {
                            "没有找到相关物品"
                        } else {
                            "没有找到相关物品（可试试清空筛选）"
                        },
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = uiState.results, key = { it.itemId }) { row ->
                            ItemRow(
                                name = row.name,
                                highlightRange = row.highlightRange,
                                subtitle = row.subtitle,
                                showOverdue = row.isOverdue,
                                // FR-02 / US-04：长按结果行副标题可复制该物品的位置路径。
                                copyablePath = row.locationPath,
                                onClick = { onResultClick(row.itemId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * FR-22：三维筛选行（分类 / 位置 / 状态）+ 清空。
 *
 * 位置维度的 label 用**完整路径文本**（VM 给的 `pathText`）：位置可能同名，
 * 只显示末级名字会让用户分不清选的是哪一个。
 */
@Composable
private fun FilterSection(
    uiState: SearchUiState,
    onCategoryFilterSelected: (String?) -> Unit,
    onLocationFilterSelected: (String?) -> Unit,
    onStatusFilterSelected: (ItemStatus?) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp)) {
        FilterChips(
            label = "分类",
            options = uiState.filterChoices.categories.map { FilterOption(it.id, it.label) },
            selectedId = uiState.filters.categoryId,
            onSelect = onCategoryFilterSelected,
        )

        FilterChips(
            label = "位置",
            options = uiState.filterChoices.locations.map { FilterOption(it.id, it.label) },
            selectedId = uiState.filters.locationId,
            // 组件回传 null = 取消该维度（再次点击已选 chip）。
            onSelect = onLocationFilterSelected,
        )

        FilterChips(
            label = "状态",
            options = uiState.filterChoices.statuses.map { FilterOption(it.id, it.label) },
            selectedId = uiState.filters.status?.code,
            onSelect = { code -> onStatusFilterSelected(code?.let { ItemStatus.fromCode(it) }) },
        )

        // 已筛选才给「清空」—— 三维皆空时它是个空操作，不占位置。
        if (!uiState.filters.isEmpty) {
            TextButton(onClick = onClearFilters) {
                Text(text = "清空筛选")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}
