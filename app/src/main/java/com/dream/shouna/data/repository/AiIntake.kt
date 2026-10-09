package com.dream.shouna.data.repository

import com.dream.shouna.data.remote.ai.AiRequest
import com.dream.shouna.data.remote.ai.AiScene
import com.dream.shouna.domain.ai.AiDraft
import com.dream.shouna.domain.ai.AiDraftRow
import com.dream.shouna.domain.ai.AiLocationNode
import com.dream.shouna.domain.ai.LocationPathMatcher
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.util.LocationPath
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * **AI 录入的用例层**：一句话 → 草稿行 → （用户确认后）落库。
 *
 * 【为什么要有这一层】它有两个调用方 —— 连续录入页 P-ADD（`V2-FR-02` 文字 / `V2-FR-10` 语音）
 * 与首页 P-HOME（首页语音入口）。被抽出来的这两段（**位置匹配**与**批量落库 + 待新建位置逐层创建**）
 * 里踩过的坑不少：同批「待新建」层要复用同一个节点、位置解析不出的行必须跳过（位置必填是不变量）、
 * 单条写库失败不能打断整批。留在两处各写一遍 ⇒ 以后只有一处被修好。
 *
 * 【为什么不放 `domain/`】本类要读位置树、分类名单，还要过 `AiGate` 发请求 —— 依赖仓库层。
 * 工程的分层（`docs/code-map/00-索引.md` §2）里 `domain/` 是最内层的纯 Kotlin，故落在仓库层。
 *
 * 【为什么"展示类派生"在这里算而不在 Composable 里】守 `实现约束.md` §2-2：组件只画不判。
 * 本类拿得到位置树与分类名单，`AiDraftRow`（含「命中 / 待新建」判定与展示文本）因此在这里组装好。
 *
 * 【AI 产出永不直接落库】本类只提供 [parseText]（产出草稿）与 [commitRows]（写库）；
 * **两者之间的「用户点确认」这一步永远在页面里**（`V2-FR-04`）。
 */
@Singleton
class AiIntake @Inject constructor(
    private val itemRepository: ItemRepository,
    private val locationRepository: LocationRepository,
    private val categoryRepository: CategoryRepository,
    private val aiGate: AiGate,
) {

    /** 位置路径 → 命中链 / 待新建层（纯函数、无状态）。本类内留一份即可，不进 DI。 */
    private val locationMatcher = LocationPathMatcher()

    /**
     * 文字 / 语音通道：一句话 → 草稿行。
     *
     * 语音与文字**走同一条**（`AiScene.TEXT_INPUT`）：语音只是把话变成文本（`V2-FR-10`），
     * 进模型的输入完全一样。
     *
     * @return `null` = **失败**（未开启 / 无密钥 / 网络 / 响应不可解析）；**空列表** = 调用成功
     *   但一件都没识别出。两者在下游的处置不同（前者与后者各给一句不同的提示），故不能合并。
     */
    suspend fun parseText(text: String): List<AiDraftRow>? {
        // 位置清单取**全量**位置树（口径见 `docs/code-map/05-改动须知.md`）：全量只在位置增删时变，
        // 而「跟随当前选中位置」的子集会随每次选择而变，键一变缓存命中率就塌，且截断会缺中间层。
        val tree = locationRepository.observeTree().first()
        val categoryList = categoryRepository.observeCategories().first()

        val drafts = runOrNull(
            AiRequest(
                input = text,
                scene = AiScene.TEXT_INPUT,
                categoryNames = categoryList.map { it.name },
                locationPaths = tree.map { it.pathText },
            ),
        ) ?: return null
        if (drafts.isEmpty()) return emptyList()

        val nodes = tree.map {
            AiLocationNode(
                id = it.location.id,
                name = it.location.name,
                parentId = it.location.parentId,
            )
        }
        return drafts.map { toRow(it, nodes, tree, categoryList) }
    }

    /**
     * 照片通道：一张图 → 草稿行（`V2-FR-11`）。
     *
     * 【为什么不附位置清单、也不读位置树】`V2-FR-13`「位置先定后用」—— 位置在拍照之前就定了，
     * 模型既不需要、也不该看到位置清单（契约段亦强制 `location_path = null`）。因此**不需要**位置
     * 树参与匹配：`toRow` 传空树即可，少一次查询。
     *
     * 【[locationId] / [locationPath]：用户先定的位置怎么进到草稿里】模型这一路**永远不返回位置**，
     * 所以照片通道的位置只有一个合法来源 —— 用户在拍照前选定的那一个。给定时**把每一行覆写**成
     * 该位置（`pendingLocationNames` 同时清空：这个位置已在库里，不存在「待新建」的层）。
     *
     * 覆写而不是留给调用方兜底，是为了让**落库路径零分支**：`commitRows` 的 `fallbackLocationId`
     * 仍旧只管「模型给了路径但没匹配上」这一种情形，两页的 `onAiApply` 因此逐字相同。
     * 顺带把卡片上的位置列填对 —— 首页那张卡若位置列空着，用户无从确认东西记到哪。
     *
     * @param locationId 用户在拍照前选定的位置 id；`null` = 调用方不给位置（**录入页**的现状，
     *   它靠 `commitRows` 的 `fallbackLocationId` 兜底，行为不变）。
     * @return 同 [parseText]：`null` = 失败，空列表 = 没辨认出任何物品。
     */
    suspend fun parsePhoto(
        imageBase64: String,
        locationId: String? = null,
        locationPath: String? = null,
    ): List<AiDraftRow>? {
        val categoryList = categoryRepository.observeCategories().first()
        val drafts = runOrNull(
            AiRequest(
                input = "",
                scene = AiScene.PHOTO_INPUT,
                categoryNames = categoryList.map { it.name },
                // 照片通道不带位置清单（`V2-FR-13`）；`AiPrompt` 另有一道兜底，不靠调用方自觉。
                locationPaths = emptyList(),
                imageBase64 = imageBase64,
            ),
        ) ?: return null
        if (drafts.isEmpty()) return emptyList()

        return drafts.map { draft ->
            val row = toRow(draft, emptyList(), emptyList(), categoryList)
            if (locationId == null) {
                row
            } else {
                row.copy(
                    locationId = locationId,
                    locationText = locationPath,
                    pendingLocationNames = emptyList(),
                )
            }
        }
    }

    /**
     * 把**一行**草稿的位置解析成可落库的节点 id：全命中 → 最深层 id；有未命中的层 → 按父链顺序
     * 逐层 `create`（§3.3 硬边界 2：**只在用户点确认之后**才建）；完全没命中 → null。
     *
     * 单件路径（录入页的「填入」会把结果回填主表单）用这个重载，它自带一个空缓存。
     */
    suspend fun resolveLocation(row: AiDraftRow): String? =
        resolveLocation(row, HashMap())

    /**
     * 批量落库：逐条 `createItemQuick`（与「保存并继续」同一个写入口径）。
     *
     * 【照片：默认不带，[photoPath] 显式给定时才带】默认 `null` = 识别用的图**不落到物品上**。
     * 录入页（含它的照片通道）走默认值 —— 那里的图已挂在**本页的 photoName** 上，用户点「保存并继续」
     * 时由那条路径落到**一件**记录上，不该在这里被 N 条记录瓜分。
     *
     * 首页「拍照AI记」走显式传值（用户 2026-10-08 裁定：识别出的每一件都挂上这张图）。**这仍然
     * 不是「复制 N 份」** —— `photo_path` 存的是**文件名**，N 条记录指向同一个文件，物理上只有一份。
     * 代价是一条记录在编辑页换 / 删图会影响到其他记录（它们共用一个文件），这是该裁定已知的取舍。
     *
     * 【位置解析不出来就跳过该条】位置必填（`docs/PRD.md`），写一条没有位置的记录会破坏
     * 这条不变量 —— 宁可少记一条，也不进一条坏的。跳过数由 [AiCommitResult] 带出去，让页面能
     * **明说**漏了几件（沉默地少记是这里最不能接受的失败方式）。
     *
     * @param fallbackLocationId 草稿位置解析不出时的兜底位置。**首页语音通道传 null**（首页没有
     *   「已选位置」这个概念），录入页传当前选中位置。照片通道的位置已在 [parsePhoto] 里写进
     *   每一行，不需要靠这里兜底。
     * @param photoPath 已存好的照片**文件名**（`PhotoStore` 的产物）；`null` = 不挂图。本方法只
     *   落库、不碰文件系统。
     */
    suspend fun commitRows(
        rows: List<AiDraftRow>,
        fallbackLocationId: String?,
        photoPath: String? = null,
    ): AiCommitResult {
        // 同一批内同名的待新建层复用同一个节点：两条草稿都写「家/客厅/抽屉」时不该建出两个「抽屉」。
        val createdCache = HashMap<String, String>()
        var saved = 0
        var skippedNoLocation = 0
        var failed = 0

        rows.forEach { row ->
            val name = row.name
            val locationId = if (name.isNotBlank()) {
                resolveLocation(row, createdCache) ?: fallbackLocationId
            } else {
                null
            }
            if (locationId == null) {
                skippedNoLocation++
                return@forEach
            }
            try {
                itemRepository.createItemQuick(
                    name = name,
                    categoryId = row.categoryId,
                    // 空白备注归一为 null（StoredItem.note 可空）。
                    note = row.note?.trim()?.ifBlank { null },
                    locationId = locationId,
                    // 默认不挂图；首页「拍照AI记」显式传入同一文件名（见本方法注释）。
                    photoPath = photoPath,
                )
                saved++
            } catch (_: Throwable) {
                // 单条失败不打断整批：其余照写（守 FR-10 零弹窗）。
                failed++
            }
        }
        return AiCommitResult(saved = saved, skippedNoLocation = skippedNoLocation, failed = failed)
    }

    /**
     * 过闸门取草稿，**失败一律收敛成 null**（`V2-FR-07`：失败回落人工，不阻塞、不弹窗）。
     *
     * 【为什么在用例层兜而不在各页面兜】两个调用方（录入页 / 首页）都必须有这一层，抄两份迟早在
     * 一处漏掉。`AiGate` 已把「未开启 / 无密钥 / 非 2xx / JSON 非法」收敛为 null，但网络 IO 本身
     * 仍可能抛（`IOException` 等）—— 在这里一并兜住。
     *
     * 【协程取消必须放行】`CancellationException` 是协程的**正常退出信号**，吞掉它会让上层
     * `viewModelScope` 的取消失效（页面已销毁却还在跑）。
     */
    private suspend fun runOrNull(request: AiRequest): List<AiDraft>? = try {
        aiGate.run(request)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        null
    }

    /**
     * 带**复用缓存**的位置解析（供 [commitRows] 在一批内共享）。键 = `父id + 层名`，避免同名层
     * 被重复创建。中途建失败时返回**已建到的最深 id** —— 已建的层是用户看过的、确定要的，
     * 不该整体放弃。
     */
    private suspend fun resolveLocation(
        row: AiDraftRow,
        createdCache: MutableMap<String, String>,
    ): String? {
        if (row.pendingLocationNames.isEmpty()) return row.locationId
        var parentId = row.locationId
        var resolved: String? = row.locationId
        row.pendingLocationNames.forEach { layerName ->
            val key = parentId.orEmpty() + CACHE_KEY_SEPARATOR + layerName
            val id = createdCache[key] ?: run {
                val created = try {
                    locationRepository.create(name = layerName, parentId = parentId)
                } catch (_: Throwable) {
                    return resolved
                }
                created.id.also { createdCache[key] = it }
            }
            parentId = id
            resolved = id
        }
        return resolved
    }

    /**
     * 草稿 → 卡片行（`实现约束.md` §2-2）。
     *
     * 位置文本取谁：**全部命中**时用树里的权威路径（模型给的分段可能带大小写 / 空白差异）；
     * **有未命中的层**时用模型给的分段，并在卡片上标「待新建」—— 那一层此刻库里并不存在，
     * 展示树的路径反而会显示不出这一层（`V2-FR-09` / §3.3 硬边界 2：确认前库中零新增）。
     */
    private fun toRow(
        draft: AiDraft,
        nodes: List<AiLocationNode>,
        tree: List<LocationTreeRow>,
        categoryList: List<Category>,
    ): AiDraftRow {
        val match = locationMatcher.match(draft.locationPath, nodes)
        val matchedPath = match.matchedId
            ?.let { id -> tree.firstOrNull { it.location.id == id }?.pathText }
        val modelPath = draft.locationPath?.joinToString(LocationPath.SEPARATOR)
        return AiDraftRow(
            name = draft.name.orEmpty().trim(),
            locationText = if (!match.hasPending && matchedPath != null) matchedPath else modelPath,
            locationId = match.matchedId,
            pendingLocationNames = match.pendingNames,
            categoryName = draft.category,
            // 分类名已由 `DraftParser` 校正成**名单里的原名**，此处按名字回推 id 是安全的。
            categoryId = draft.category?.let { name -> categoryList.firstOrNull { it.name == name }?.id },
            note = draft.note,
        )
    }

    private companion object {
        /** 同一批落库中，待新建层的复用键分隔符（父 id 与层名之间）。 */
        const val CACHE_KEY_SEPARATOR = '\u0000'
    }
}

/**
 * 一次批量落库的结果。
 *
 * 【为什么要把「没记下的」带出来】位置必填 + 单条失败不打断整批，这两条都会让**实际入库数少于
 * 用户勾选数**。页面据此给一句汇总，否则用户以为全记下了（静默少记是这里最坏的失败）。
 *
 * @param saved 成功写库的件数
 * @param skippedNoLocation 因无任何位置线索而跳过的件数
 * @param failed 因写库异常而失败的件数
 */
data class AiCommitResult(
    val saved: Int = 0,
    val skippedNoLocation: Int = 0,
    val failed: Int = 0,
) {
    /** 勾选了但没进库的总数。 */
    val notSaved: Int get() = skippedNoLocation + failed
}
