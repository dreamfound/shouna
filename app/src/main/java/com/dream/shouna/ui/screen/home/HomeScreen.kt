package com.dream.shouna.ui.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.R
import com.dream.shouna.ui.component.EmptyState
import com.dream.shouna.ui.component.ShounaSearchBar
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.goLocationBrowse
import com.dream.shouna.ui.navigation.goPhotoAiIntake
import com.dream.shouna.ui.navigation.goQuickAdd
import com.dream.shouna.ui.navigation.goSearch
import com.dream.shouna.ui.navigation.goSettings
import com.dream.shouna.ui.navigation.goStats
import com.dream.shouna.ui.navigation.goVoiceAiIntake

/**
 * 有状态包装层（docs/实现约束.md）。
 * P1：「更多」由 1 项扩到 3 项，新增的统计 / 设置两条导航动作在本层下发。
 * V2：两个 AI 入口各只下发一条**导航**动作 —— 语音句录入与拍照识别录入都已从「首页的一条通道」
 * 改为**独立页面**（`ui/screen/voiceai/`、`ui/screen/photoai/`），本页不再持有任何 AI 状态、
 * 语音弹窗、位置树与弹层。
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
        onStatsClick = { navController.goStats() },
        onSettingsClick = { navController.goSettings() },
        onVoiceAiClick = { navController.goVoiceAiIntake() },
        onPhotoAiClick = { navController.goPhotoAiIntake() },
    )
}

/**
 * 无状态页：搜索框（点击进搜索页并聚焦）+「＋ 记一件」+「🎤 语音AI记」+「📷 拍照AI记」+
 * 「更多」折叠区。
 *
 * 「更多」是 F2 入口的容器（docs/PRD.md）：P1 起由 **1 项 → 3 项**（位置浏览 / 归纳统计 / 设置）。
 * **不做阈值门控** —— 首启已预置 6 个位置，「位置总数 ≥ 3 才显示」恒真，门控是死代码（P0 §8.1-8）。
 *
 * 【两个 AI 入口都只做导航，各自的页面里完成全部过程】用户 2026-10-09 口径：语音与拍照用
 * **同一种记录方式** —— 入口进独立页、结果走**逐条确认弹窗**、确认一件落库一件。首页因此
 * 不再有 AI 状态区、确认卡与落库汇总。
 *
 * - `V2-FR-10` 语音：「🎤 语音AI记」→ P-VOICE-AI（页内长按说话 → 识别 → 逐条弹窗）。
 *   位置由**那句话**决定，猜错可在弹窗里就地改。
 * - `V2-FR-11` / `V2-FR-13` 拍照：「📷 拍照AI记」→ P-PHOTO-AI（先定位置 → 拍照 → 识别 →
 *   逐条弹窗）。位置由**用户先定**，弹窗里只读。说不清东西放在哪时，拍一张、自己挑位置。
 *
 * 【可见性】两个入口**都只在 AI 总开关开启时出现**。开关关着时连拆句都做不了，造一个点了没用的
 * 入口比不出现更糟（也与「开关关着时不新增任何界面、零网络请求」这条硬约束一致）。
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onSearchClick: () -> Unit,
    onQuickAddClick: () -> Unit,
    onBrowseLocationsClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onVoiceAiClick: () -> Unit,
    onPhotoAiClick: () -> Unit,
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

        // V2 的两个 AI 入口，顺序与「越靠说、越靠拍」的直觉一致：说一句在前（更快），拍照在后。
        // 开关关着时整块不出现（零新增 UI、零请求）。
        if (uiState.aiEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onVoiceAiClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.ai_home_voice_entry))
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onPhotoAiClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.ai_home_photo_entry))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = { moreExpanded = !moreExpanded }) {
            Text(text = if (moreExpanded) "收起" else "更多")
        }

        if (moreExpanded) {
            TextButton(onClick = onBrowseLocationsClick) {
                Text(text = "位置浏览")
            }
            TextButton(onClick = onStatsClick) {
                Text(text = "归纳统计")
            }
            TextButton(onClick = onSettingsClick) {
                Text(text = "设置")
            }
        }

        if (uiState.isEmpty) {
            EmptyState(text = "还没有记录，点「＋ 记一件」开始")
        }
    }
}
