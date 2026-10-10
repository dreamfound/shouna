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
 * 【为什么要有这一层】它有三个调用方 —— 连续录入页 P-ADD（`V2-FR-02` 文字 / `V2-FR-10` 语音的
 * V1 形态）、语音 AI 录入页 P-VOICE-AI（`V2-FR-10`）、拍照 AI 录入页 P-PHOTO-AI（`V2-FR-11`）。
 * 被抽出来的这两段（**位置匹配**与**批量落库 + 待新建位置逐层创建**）
 * 里踩过的坑不少：同批「待新建」层要复用同一个节点、位置解析不出的行必须跳过（位置必填是不变量）、
 * 单条写库失败不能打断整批。留在几处各写一遍 ⇒ 以后只有一处被修好。
 *
 * 【为什么不放 `domain/`】本类要读位置树、分类名单，还要过 `AiGate` 发请求 —— 依赖仓库层。
 * 工程的分层（`docs/code-map/00-索引.md` §2）里 `domain/` 是最内层的纯 Kotlin，故落在仓库层。
 *
 * 【为什么"展示类派生"在这里算而不在 Composable 里】守 `实现约束.md` §2-2：组件只画不判。
 * 本类拿得到位置树与分类名单，`AiDraftRow`（含「命中 / 待新建 / 已降级」判定与展示文本）
 * 因此在这里组装好。
 *
 * 【AI 产出永不直接落库】本类只提供 [parseText] / [parseSpokenText] / [parsePhoto]（产出草稿）
 * 与 [commitRows]（写库）；
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
     * 录入页（P-ADD）的**文字通道**：一句话 → 草稿行（`V2-FR-02`）。
     *
     * 【[createMissingLayers]：未命中的尾部层「新建」还是「丢弃」】两条口径的分界就在这个开关：
     * - `true`（默认，**录入页** `V2-FR-02` / `V2-FR-09` 的既有口径）：那几层标「待新建」，
     *   用户点确认后由 [commitRows] 逐层 `create`（`V2-AI-PRD` §3.3 硬边界 2）；
     * - `false`：模型说的层库里没有就**不建**，直接**降级到最近的那个已存在祖先**
     *   （「客厅 › 抽屉」而「抽屉」不存在 ⇒ 记到「客厅」）；连一个祖先都没对上时该行没有位置
     *   ⇒ 弹窗里由用户**就地补一个**（补不上就跳过，位置必填是 `docs/PRD.md` 的不变量）。
     *   被丢弃的层名进 [AiDraftRow.droppedLocationNames]，页面据此把「已就近」讲给用户听。
     *   语音通道（[parseSpokenText]）走的就是这一支。
     *
     * @return `null` = **失败**（未开启 / 无密钥 / 网络 / 响应不可解析）；**空列表** = 调用成功
     *   但一件都没识别出。两者在下游的处置不同（前者与后者各给一句不同的提示），故不能合并。
     */
    suspend fun parseText(
        text: String,
        createMissingLayers: Boolean = true,
    ): List<AiDraftRow>? = parseTextWith(text, AiScene.TEXT_INPUT, createMissingLayers)

    /**
     * 语音 AI 录入页（P-VOICE-AI）的**语音句通道**：一句话 → 草稿行（`V2-FR-10`）。
     *
     * 【为什么单独一个入口，而不是让页面传参】语音这一路有两个口径与文字通道不同，都在这里收口，
     * 页面只需「把识别到的字交进来」：
     * ① **场景独立**（`AiScene.VOICE_INPUT`）—— 语音页有自己的功能段（用户 2026-10-09 口径）；
     *    `AiGate` 的缓存键含场景 ⇒ 同一句话「在录入页打字」与「在语音页说出来」不会互相命中缓存；
     * ② **位置不新建**（`createMissingLayers = false`）—— 见 [parseText] 的说明。
     */
    suspend fun parseSpokenText(text: String): List<AiDraftRow>? =
        parseTextWith(text, AiScene.VOICE_INPUT, createMissingLayers = false)

    // 【2026-10-10 停用 · 注释保留】录音通道（同一页的另一条输入形态）—— 平台不受理音频输入，
    // 随整条录音链路一并注释。它与 [parseSpokenText] 的位置口径逐字相同，只有「送什么给模型」
    // 不同。恢复时去掉下面各行行首的 `// ` 即可：
    //
    // /**
    //  * 同一页的**录音通道**：一段录音 → 草稿行（`V2-FR-10`，2026-10-10 新增）。
    //  *
    //  * 【与 [parseSpokenText] 的关系：位置口径完全相同，只有「送什么给模型」不同】两者的
    //  * ①场景各自独立（`VOICE_AUDIO` 与 `VOICE_INPUT`）、②位置不新建（`createMissingLayers = false`）
    //  * 两条口径**逐字相同** —— 用户 2026-10-10 的口径是「录音直接上行，不走转文字」，**换掉的只是
    //  * 输入形态**，位置那一套（降级到最近已存在祖先、丢弃的层名进 `droppedLocationNames`）原样沿用。
    //  *
    //  * 【为什么不能复用 `VOICE_INPUT`】报文形态不同（音频是 `[text, input_audio]` 数组），
    //  * 且缓存键含场景 —— 复用会让「同一段内容，一次打字、一次录成音频」互相命中。
    //  */
    // suspend fun parseSpokenAudio(audioBase64: String): List<AiDraftRow>? =
    //     parseAudioWith(audioBase64, AiScene.VOICE_AUDIO, createMissingLayers = false)

    /** 文字通道的薄包装（保持既有调用点不变）。 */
    private suspend fun parseTextWith(
        text: String,
        scene: AiScene,
        createMissingLayers: Boolean,
    ): List<AiDraftRow>? = parseWith(createMissingLayers) { tree, categoryList ->
        AiRequest(
            input = text,
            scene = scene,
            categoryNames = categoryList.map { it.name },
            locationPaths = tree.map { it.pathText },
        )
    }

    // 【2026-10-10 停用 · 注释保留】录音通道的薄包装（`input` 恒空、音频进 `AiRequest.audioBase64`）
    // —— 平台不受理音频输入，随整条录音链路一并注释。恢复时去掉下面各行行首的 `// ` 即可：
    //
    // /** 录音通道的薄包装：`input` 恒空（那句话是网络层补的默认指令）、音频进 [AiRequest.audioBase64]。 */
    // private suspend fun parseAudioWith(
    //     audioBase64: String,
    //     scene: AiScene,
    //     createMissingLayers: Boolean,
    // ): List<AiDraftRow>? = parseWith(createMissingLayers) { tree, categoryList ->
    //     AiRequest(
    //         input = "",
    //         scene = scene,
    //         categoryNames = categoryList.map { it.name },
    //         locationPaths = tree.map { it.pathText },
    //         audioBase64 = audioBase64,
    //     )
    // }

    /**
     * 三个「读清单 → 过闸门 → 组装草稿行」入口的**共同实现** —— 差别只有「建不建未命中的层」
     * 与「[AiRequest] 怎么造」这两处，后者由调用方以 `buildRequest` 给出。
     *
     * 位置清单取**全量**位置树（口径见 `docs/code-map/05-改动须知.md`）：全量只在位置增删时变，
     * 而「跟随当前选中位置」的子集会随每次选择而变，键一变缓存命中率就塌，且截断会缺中间层。
     */
    private suspend fun parseWith(
        createMissingLayers: Boolean,
        buildRequest: (tree: List<LocationTreeRow>, categoryList: List<Category>) -> AiRequest,
    ): List<AiDraftRow>? {
        val tree = locationRepository.observeTree().first()
        val categoryList = categoryRepository.observeCategories().first()

        val drafts = runOrNull(buildRequest(tree, categoryList)) ?: return null
        if (drafts.isEmpty()) return emptyList()

        val nodes = tree.map {
            AiLocationNode(
                id = it.location.id,
                name = it.location.name,
                parentId = it.location.parentId,
            )
        }
        return drafts.map { toRow(it, nodes, tree, categoryList, createMissingLayers) }
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
     * 仍旧只管「模型给了路径但没匹配上」这一种情形。顺带把逐条弹窗上的位置行填对 ——
     * 那一行若空着，用户无从确认东西记到哪。
     *
     * @param locationId 用户在拍照前选定的位置 id；`null` = 调用方不给位置。
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
            // `createMissingLayers = true`：本通道的匹配结果**整块**被下面那次 `copy` 覆盖
            // （位置是用户先定的，模型这一路永不返回位置），取哪个值都不影响落点；取 `true`
            // 是为了不落下 `droppedLocationNames` 标记 —— 那个标记专属于语音通道的降级口径。
            val row = toRow(draft, emptyList(), emptyList(), categoryList, createMissingLayers = true)
            if (locationId == null) {
                row
            } else {
                row.copy(
                    locationId = locationId,
                    locationText = locationPath,
                    pendingLocationNames = emptyList(),
                    droppedLocationNames = emptyList(),
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
     * 拍照 AI 录入页走显式传值（用户 2026-10-08 裁定：识别出的每一件都挂上这张图）。**这仍然
     * 不是「复制 N 份」** —— `photo_path` 存的是**文件名**，N 条记录指向同一个文件，物理上只有一份。
     * 代价是一条记录在编辑页换 / 删图会影响到其他记录（它们共用一个文件），这是该裁定已知的取舍。
     *
     * 【位置解析不出来就跳过该条】位置必填（`docs/PRD.md`），写一条没有位置的记录会破坏
     * 这条不变量 —— 宁可少记一条，也不进一条坏的。跳过数由 [AiCommitResult] 带出去，让页面能
     * **明说**漏了几件（沉默地少记是这里最不能接受的失败方式）。
     *
     * 【逐条调用是安全的】`createdCache` 的寿命是**一次调用**，而语音通道已改成不新建位置
     * （[parseSpokenText]）⇒ 两页传进来的行 `pendingLocationNames` 恒空，
     * 逐条调用不会建出重复节点。**将来若放开「未命中层新建」+「逐条落库」的组合，必须把
     * `createdCache` 提到调用方按轮次持有**，否则同一层会被建出多个。
     *
     * @param fallbackLocationId 草稿位置解析不出时的兜底位置。**语音通道与拍照通道都传 null**
     *   （两者的位置分别由那句话 / 用户先定决定，都不需要兜底），录入页传当前选中位置。
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
                    // 默认不挂图；拍照 AI 录入页显式传入同一文件名（见本方法注释）。
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
     * 草稿 → 弹窗条目 / 卡片行（`实现约束.md` §2-2）。
     *
     * 位置文本取谁：**全部命中**时用树里的权威路径（模型给的分段可能带大小写 / 空白差异）；
     * **有未命中的层**时用模型给的分段，并在卡片上标「待新建」—— 那一层此刻库里并不存在，
     * 展示树的路径反而会显示不出这一层（`V2-FR-09` / §3.3 硬边界 2：确认前库中零新增）。
     * **降级**（[createMissingLayers] = false）时反过来必须用**树里的权威路径**：
     * 用户看到的落点必须与落库的那个位置逐字一致，不能停留在模型说的那一串上。
     */
    private fun toRow(
        draft: AiDraft,
        nodes: List<AiLocationNode>,
        tree: List<LocationTreeRow>,
        categoryList: List<Category>,
        createMissingLayers: Boolean,
    ): AiDraftRow {
        val match = locationMatcher.match(draft.locationPath, nodes)
        val matchedPath = match.matchedId
            ?.let { id -> tree.firstOrNull { it.location.id == id }?.pathText }
        val modelPath = draft.locationPath?.joinToString(LocationPath.SEPARATOR)
        // 未命中的尾部层：新建（录入页）还是丢弃（语音页）—— 两者只能有一个非空。
        val pendingNames = if (createMissingLayers) match.pendingNames else emptyList()
        val droppedNames = if (createMissingLayers) emptyList() else match.pendingNames
        return AiDraftRow(
            name = draft.name.orEmpty().trim(),
            locationText = if (pendingNames.isEmpty() && matchedPath != null) matchedPath else modelPath,
            locationId = match.matchedId,
            pendingLocationNames = pendingNames,
            droppedLocationNames = droppedNames,
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
