package com.dream.shouna.ui.screen.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.CategoryRepository
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.data.repository.LocationRepository
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.Location
import com.dream.shouna.domain.model.LocationTreeRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * FR-09 / FR-10 / FR-12 + **位置必填**（ARCHITECTURE-P0 §0）的状态机。
 *
 * 位置必填的三条规则在此落地：
 * ① 位置条默认**预选最近使用**的位置（`last_used_at` 最近者）；首次安装无使用史 → 未选 → 不可保存。
 * ② 未选位置时 [QuickAddUiState.canSave] 恒为 false → 「保存并继续」不可点，用户必须先选或新建。
 * ③ 保存成功后**保留**位置（只清名称 / 分类 / 备注）→ 连续录入沿用同一位置，不打断（PRD US-02）。
 *
 * 被调用方：QuickAddRoute（hiltViewModel + 方法引用下传）
 */
@HiltViewModel
class QuickAddViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val locationRepository: LocationRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    private val state = MutableStateFlow(QuickAddUiState())

    val uiState: StateFlow<QuickAddUiState> = state.asStateFlow()

    /** FR-12：内置分类 chips 数据源。 */
    val categories: StateFlow<List<Category>> = categoryRepository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), emptyList())

    /** 位置选择弹层的树数据（已展平、已过滤内置哨兵）。 */
    val locationTree: StateFlow<List<LocationTreeRow>> = locationRepository.observeTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), emptyList())

    /** FR-11：最近使用的位置（弹层内的「最近」chips）。 */
    private val recent = MutableStateFlow<List<Location>>(emptyList())
    val recentLocations: StateFlow<List<Location>> = recent.asStateFlow()

    private val picker = MutableStateFlow(false)
    val isLocationPickerVisible: StateFlow<Boolean> = picker.asStateFlow()

    init {
        // FR-11：进入录入页即预选最近使用的位置。首次安装返回空 → 保持未选（必须手动选择）。
        viewModelScope.launch {
            val recentList = locationRepository.recentUsed()
            recent.value = recentList
            recentList.firstOrNull()?.let { location ->
                state.update { it.copy(selectedLocationId = location.id) }
                recomputeCanSave()
            }
        }

        // 路径文本跟随位置树刷新：改名 / 新建后无需重新选择即自动更新（FR-03 的即时跟随）。
        viewModelScope.launch {
            locationRepository.observeTree().collect { tree ->
                val selectedId = state.value.selectedLocationId ?: return@collect
                val pathText = tree.firstOrNull { it.location.id == selectedId }?.pathText.orEmpty()
                state.update { it.copy(selectedLocationPath = pathText) }
            }
        }
    }

    fun onNameChange(value: String) {
        state.update { it.copy(inputName = value) }
        recomputeCanSave()
    }

    fun onCategorySelected(categoryId: String?) {
        // categoryId = null 即「再次点击取消选中」（FR-12 可跳过）。
        state.update { it.copy(selectedCategoryId = categoryId) }
    }

    fun onNoteChange(value: String) {
        // 原样保存输入，空白归一延迟到落库时（见 onSaveAndContinue）。
        state.update { it.copy(note = value) }
    }

    fun onOpenLocationPicker() {
        picker.value = true
    }

    fun onDismissLocationPicker() {
        picker.value = false
    }

    /** 在树里选中一个已有位置。 */
    fun onLocationSelected(locationId: String) {
        state.update { it.copy(selectedLocationId = locationId) }
        recomputeCanSave()
        picker.value = false
    }

    /**
     * 「或输入位置」：新建一个位置节点并立即选中。
     * 父级 = **当前已选位置**（未选则建在根级）—— 这样「输入位置」总是落在用户正在看的层级下。
     */
    fun onCreateLocation(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val created = locationRepository.create(
                name = trimmed,
                parentId = state.value.selectedLocationId,
            )
            recent.value = locationRepository.recentUsed()
            state.update {
                it.copy(selectedLocationId = created.id, selectedLocationPath = created.name)
            }
            recomputeCanSave()
            picker.value = false
        }
    }

    /** 「保存并继续」：清空名称 / 分类 / 备注，**保留位置**，计数器 +1，零弹窗。 */
    fun onSaveAndContinue() {
        val current = state.value
        if (!current.canSave) return
        val locationId = current.selectedLocationId ?: return

        viewModelScope.launch {
            try {
                itemRepository.createItemQuick(
                    name = current.inputName.trim(),
                    categoryId = current.selectedCategoryId,
                    // 空白备注归一为 null（StoredItem.note 可空）。
                    note = current.note?.trim()?.ifBlank { null },
                    locationId = locationId,
                )
            } catch (_: Throwable) {
                // FR-10「零弹窗」：不做任何错误弹窗，保持输入原样、计数器不动，由用户重试。
                return@launch
            }
            // 清空输入（**保留位置**）；计数器 +1；焦点由 QuickAddScreen 依 sessionCount 变化重新请求。
            state.update {
                it.copy(
                    inputName = "",
                    selectedCategoryId = null,
                    note = null,
                    sessionCount = it.sessionCount + 1,
                )
            }
            recomputeCanSave()
            // 这次录入刷新了 last_used_at → 同步一次「最近」，使弹层顺序立刻正确。
            recent.value = locationRepository.recentUsed()
        }
    }

    /** canSave 口径（位置必填）：**名称非空 ∧ 位置已选**。 */
    private fun recomputeCanSave() {
        state.update {
            it.copy(canSave = it.inputName.isNotBlank() && it.selectedLocationId != null)
        }
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}

data class QuickAddUiState(
    val inputName: String = "",
    val selectedCategoryId: String? = null,
    val note: String? = null,
    val sessionCount: Int = 0,
    /** **名称非空 ∧ 位置已选** 才为 true（位置必填，ARCHITECTURE-P0 §0）。 */
    val canSave: Boolean = false,
    /** 已选位置 id；null = 未选（不可保存）。 */
    val selectedLocationId: String? = null,
    /** 已选位置的面包屑文本；未选为空串。 */
    val selectedLocationPath: String = "",
)
