package com.dream.shouna.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * P1-04：别名编辑（chips 增删）。
 *
 * 无状态到「值由外部下传」这一层，但**草稿输入**（还没提交的待添加别名）留在组件内 ——
 * 那是纯输入态，不构成业务判定。
 *
 * 上限于去重的判断**不在本组件**：`max` 只用于把「已达上限」显示出来并禁用新增入口，
 * 真正的去重 / 与名称相同 / 上限拦截落在 `ItemEditViewModel`（守 `实现约束.md` §3-4）。
 */
@Composable
fun AliasEditor(
    aliases: List<String>,
    max: Int,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    val reachedLimit = aliases.size >= max

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "别名（$max 个以内，可用来搜到同一件东西）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (aliases.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                items(items = aliases, key = { it }) { alias ->
                    // 直接点击 chip 即删除：别名数量少、且改错代价低，不再加一层二次确认
                    // （`实现约束.md` §4-2 的二次确认针对的是**破坏性**操作，删一个别名不是）。
                    FilterChip(
                        selected = false,
                        onClick = { onRemove(alias) },
                        label = { Text(text = "$alias ✕") },
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                enabled = !reachedLimit,
                label = { Text(text = if (reachedLimit) "已达上限" else "新增别名") },
                modifier = Modifier.weight(1f),
            )

            TextButton(
                onClick = {
                    onAdd(draft)
                    draft = ""
                },
                enabled = !reachedLimit && draft.isNotBlank(),
            ) {
                Text(text = "添加")
            }
        }
    }
}
