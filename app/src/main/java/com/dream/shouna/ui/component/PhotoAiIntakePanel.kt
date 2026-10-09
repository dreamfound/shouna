package com.dream.shouna.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dream.shouna.R

/**
 * 首页「拍照AI记」的**页内面板**（`V2-FR-11` + `V2-FR-13`）：位置条 + 照片行 + 识别按钮。
 *
 * 【为什么是页内一段，不是弹窗】① 面板里要开位置选择，而它本身是个 `ModalBottomSheet` ——
 * 弹窗套弹层是两个独立窗口叠着，测量与返回键都要额外照顾；② 本工程实测按需创建的新弹层冷首帧
 * 约 700ms（`LocationPickerSheet` 冷 734.6 / 温 520.7ms），页内展开只承担重组成本；③ 与首页
 * 语音入口同形态 —— 首页只该有一种「AI 入口长什么样」。
 *
 * 【三件事的先后是硬口径】**位置 → 照片 → 识别**（`V2-FR-13`「位置先定后用」）。位置排在照片
 * 之上不只是版面顺序：模型这一路**永远不返回位置**，识别出的每一件都落到用户先定的那个位置，
 * 所以没有位置就没有落点，[onRecognize] 在该状态下不可点。
 *
 * 【无状态】位置文本、照片名、是否解析中全由调用方（VM）给，本组件只画与回调 —— 守
 * `实现约束.md` §2-2：展示类派生一律在 VM / 用例层，Composable 只画不判。
 * 唯一的例外是按钮可用性，那是**纯输入态推导**（照片在不在 × 位置选没选 × 是否在跑），
 * 与 `AiDraftCard` 里「一件没勾就置灰」属同一类判断。
 *
 * 【照片文件的生命周期归谁】建与删都在 `PhotoInputRow` 里（它是系统选择器的适配层），
 * **唯一例外**是「用户收起面板」那一下 —— 那时面板整块离开组合，`PhotoInputRow` 不会删任何东西
 * （它刻意不在 `onDispose` 里删，见其类注释：旋转也会触发 `onDispose`），所以收起的清理由
 * 调用方在 `onClose` 里做。已落库的照片不会走到那里：落库成功时 VM 会把照片名清空。
 *
 * 被调用方：`ui/screen/home/HomeScreen.kt`。
 */
@Composable
fun PhotoAiIntakePanel(
    photoName: String?,
    locationPath: String?,
    parsing: Boolean,
    onPickLocation: () -> Unit,
    onPhotoChanged: (String?) -> Unit,
    onRecognize: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasLocation = !locationPath.isNullOrBlank()
    val hasPhoto = photoName != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.ai_home_photo_title),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            // 收起是**唯一的删图时机**（未被落库的那张），所以它必须是一个显式的用户动作，
            // 不能由「点了别处」顺带触发。
            TextButton(onClick = onClose, enabled = !parsing) {
                Text(text = stringResource(R.string.ai_home_photo_collapse))
            }
        }

        // 位置条：必选项。未选时用错误色提示，点击打开位置树弹层（弹层内可「＋ 新建位置」）。
        // 文本直接摊开**完整面包屑路径**，不做「已选」这类占位词 —— 首页这张卡落库后用户唯一
        // 能确认「记到哪」的地方就是这里（位置与 id 由 VM 同一次写入，两者不会只到一个）。
        OutlinedButton(
            onClick = onPickLocation,
            enabled = !parsing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (hasLocation) {
                    stringResource(R.string.ai_home_photo_location, locationPath.orEmpty())
                } else {
                    stringResource(R.string.ai_home_photo_location_empty)
                },
                color = if (hasLocation) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // FR-16 的照片行原样复用：拍照（零权限）/ 从相册 / 预览 / 移除。
        // `deleteReplacedImmediately` 走默认的 true —— 面板里的图在落库前是临时介质，
        // 换掉就该删掉旧文件；落库之后 VM 会清空照片名，替换删除再也碰不到库里那份。
        PhotoInputRow(
            photoName = photoName,
            onPhotoChanged = onPhotoChanged,
            enabled = !parsing,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = onRecognize,
            enabled = hasPhoto && hasLocation && !parsing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.ai_home_photo_recognize))
        }

        // 按钮为什么是灰的，直说 —— 不让用户对着一个点不动的按钮猜（与录入页位置条同一取向）。
        if (!parsing) {
            val hint = when {
                !hasPhoto -> stringResource(R.string.ai_home_photo_need_photo)
                !hasLocation -> stringResource(R.string.ai_entry_fail_need_location)
                else -> null
            }
            if (hint != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
