package com.dream.shouna.ui.component

import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow

/**
 * FR-02 位置面包屑：展示「家 › 储物间 › 纸箱-07」，**长按复制**。
 *
 * 复制走 [LocalClipboardManager]（系统剪贴板）——**零权限**，不引入 `uses-permission`
 * （守 NFR-17）。
 *
 * 无状态：路径文本由 VM 用 `LocationPath` 派生后下传；[pathText] 为空即整行不渲染
 * （位置缺失的降级表现，而不是显示一个空壳）。
 */
@Composable
fun LocationBreadcrumb(
    pathText: String,
    modifier: Modifier = Modifier,
) {
    if (pathText.isBlank()) return

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Text(
        text = pathText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(AnnotatedString(pathText))
                    Toast.makeText(context, "已复制位置路径", Toast.LENGTH_SHORT).show()
                },
            ),
    )
}
