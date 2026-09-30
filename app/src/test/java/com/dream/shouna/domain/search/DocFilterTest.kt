package com.dream.shouna.domain.search

import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.domain.model.Location
import com.dream.shouna.domain.model.LocationTreeRow
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * FR-22 三维筛选共享实现（P1-07 从 `SearchViewModel` 抽到 `domain`）的单测。
 *
 * **为什么值得单独测**：这份实现现在被**搜索页**与**统计页「全部物品」**共用。
 * 抽出来的唯一目的就是「两个页面的筛选口径不许分裂」，所以「含子层」「状态 code 与中文 label 的
 * 对应」这些口径必须有测试钉住 —— 否则下次改一处，另一处会静默跑偏。
 */
class DocFilterTest {

    private val home = location("home", "家", parentId = null)
    private val storeroom = location("store", "储物间", parentId = "home")
    private val box = location("box", "纸箱-07", parentId = "store")
    private val garage = location("garage", "车库", parentId = null)

    private val tree = listOf(
        row(home, depth = 0, pathText = "家"),
        row(storeroom, depth = 1, pathText = "家 › 储物间"),
        row(box, depth = 2, pathText = "家 › 储物间 › 纸箱-07"),
        row(garage, depth = 0, pathText = "车库"),
    )

    private val fan = doc(
        itemId = "fan",
        name = "电风扇",
        categoryId = "appliance",
        categoryName = "电器",
        locationId = "box",
        status = ItemStatus.IN_STORAGE,
    )
    private val lamp = doc(
        itemId = "lamp",
        name = "台灯",
        categoryId = "appliance",
        categoryName = "电器",
        locationId = "store",
        status = ItemStatus.TO_BE_PUT_BACK,
    )
    private val tire = doc(
        itemId = "tire",
        name = "备胎",
        categoryId = null,
        categoryName = null,
        locationId = "garage",
        status = ItemStatus.IN_STORAGE,
    )
    private val all = listOf(fan, lamp, tire)

    @Test
    fun emptyFiltersReturnEveryDocUntouched() {
        assertThat(applyDocFilters(all, SearchFilters(), tree)).isEqualTo(all)
    }

    @Test
    fun locationFilterCoversTheWholeSubtree() {
        // 选「家」= 家自己 + 储物间 + 纸箱-07（P1 §8.1-7 的「含子层」口径）。
        val byHome = applyDocFilters(all, SearchFilters(locationId = "home"), tree)
        assertThat(byHome.map { it.itemId }).containsExactly("fan", "lamp")

        // 选「储物间」不含车库里的东西。
        val byStoreroom = applyDocFilters(all, SearchFilters(locationId = "store"), tree)
        assertThat(byStoreroom.map { it.itemId }).containsExactly("fan", "lamp")

        // 选最底层只命中它自己。
        assertThat(applyDocFilters(all, SearchFilters(locationId = "box"), tree).map { it.itemId })
            .containsExactly("fan")
    }

    @Test
    fun dimensionsCombineWithAnd() {
        val result = applyDocFilters(
            all,
            SearchFilters(categoryId = "appliance", locationId = "home", status = ItemStatus.IN_STORAGE),
            tree,
        )
        // 三个条件同时成立：电器 ∧ 家（含子层） ∧ 在存放中 → 只剩风扇。
        assertThat(result.map { it.itemId }).containsExactly("fan")
    }

    @Test
    fun statusFilterMatchesOnStatusNotOnLabel() {
        val pending = applyDocFilters(all, SearchFilters(status = ItemStatus.TO_BE_PUT_BACK), tree)
        assertThat(pending.map { it.itemId }).containsExactly("lamp")

        // 「不在了」这一支是 P1-07 要能筛出来的：口径在调用方（它决定喂哪些文档进来），
        // 筛选本身只认枚举相等。
        val gone = doc(
            itemId = "ghost",
            name = "旧台灯",
            categoryId = null,
            categoryName = null,
            locationId = "store",
            status = ItemStatus.GONE,
        )
        assertThat(applyDocFilters(all + gone, SearchFilters(status = ItemStatus.GONE), tree).map { it.itemId })
            .containsExactly("ghost")
    }

    @Test
    fun subtreeOfUnknownRootOnlyContainsItself() {
        // 并发删除后仍选中它 → 命中零条，而不是「悄悄变成不过滤」。
        assertThat(subtreeIdsOf("deleted", tree)).containsExactly("deleted")
    }

    @Test
    fun choicesCarryIdsAndChineseLabels() {
        val choices = buildDocFilterChoices(all, tree)

        assertThat(choices.categories.map { it.id }).containsExactly("appliance")
        assertThat(choices.categories.map { it.label }).containsExactly("电器")
        // 位置 label 用完整路径文本（位置可能同名，只给末级名字分不清选的是哪一个）。
        assertThat(choices.locations.map { it.label })
            .containsExactly("家", "家 › 储物间", "家 › 储物间 › 纸箱-07", "车库")
        // 状态 label 走 `filterLabel()`，两个页面共用同一份中文文案。
        assertThat(choices.statuses.map { it.id }).containsExactly("in_storage", "to_be_put_back")
        assertThat(choices.statuses.map { it.label }).containsExactly("在存放中", "待归位")
    }

    @Test
    fun goneLabelIsAvailableForTheStatusDimension() {
        // 「不在了」的文案与 code 必须与其它两态同源，否则统计页会出现一个英文 code 的 chip。
        assertThat(ItemStatus.GONE.filterLabel()).isEqualTo("不在了")
        assertThat(ItemStatus.GONE.code).isEqualTo("gone")
    }

    private fun location(id: String, name: String, parentId: String?) =
        Location(id = id, name = name, isBuiltIn = false, parentId = parentId)

    private fun row(location: Location, depth: Int, pathText: String) = LocationTreeRow(
        location = location,
        depth = depth,
        pathText = pathText,
        itemCount = 0,
        hasChildren = false,
    )

    private fun doc(
        itemId: String,
        name: String,
        categoryId: String?,
        categoryName: String?,
        locationId: String,
        status: ItemStatus,
    ) = SearchDoc(
        itemId = itemId,
        name = name,
        normalizedName = name,
        categoryId = categoryId,
        categoryName = categoryName,
        normalizedCategoryName = categoryName,
        locationId = locationId,
        status = status,
        lastModifiedAt = 0L,
    )
}
