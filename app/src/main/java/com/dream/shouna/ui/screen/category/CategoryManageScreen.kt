package com.dream.shouna.ui.screen.category

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.domain.model.Category
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.popBack

/**
 * 有状态包装层（ARCHITECTURE §2）。
 */
@Composable
fun CategoryManageRoute() {
    val viewModel: CategoryManageViewModel = hiltViewModel()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    CategoryManageScreen(
        categories = categories,
        onBack = { navController.popBack() },
    )
}

/**
 * 分类管理页（FR-44）—— **骨架**。
 *
 * 当前渲染：标题 + 只读列表（区分「内置」/「自定义」，**只读不提供操作入口**）+ 返回。
 * 与 `ItemEditScreen` 同一条边界：会走到 TODO 桩的新增 / 改名 / 删除入口一概不挂，
 * 只展示列表本身 —— 顺带也能一眼看出「内置 8 条」是否被正确读成内置。
 *
 * 未渲染（P1-05 实现期补）：新建入口、改名入口、删除入口 + 二次确认对话框、排序调整。
 */
@Composable
fun CategoryManageScreen(
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
            text = "分类管理",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (categories.isEmpty()) {
            Text(
                text = "还没有分类",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            categories.forEach { category ->
                Text(
                    text = "${category.name}（${if (category.isBuiltIn) "内置" else "自定义"}）",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "待实现（P1-05）：新建 / 改名 / 删除（删除二次确认；内置分类可改名不可删）",
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
