package com.dream.shouna.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 首页（ARCHITECTURE §2）：唯一状态 = `isEmpty`。
 *
 * 被调用方：HomeRoute（hiltViewModel）
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    itemRepository: ItemRepository,
) : ViewModel() {

    // 调用关系：ItemRepository.observeItemSummaries() → map { isEmpty } → stateIn
    // （本类是全工程唯一已接线完成的 ViewModel）
    val uiState: StateFlow<HomeUiState> = itemRepository.observeItemSummaries()
        .map { items -> HomeUiState(isEmpty = items.isEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}

data class HomeUiState(
    val isEmpty: Boolean = true,
)
