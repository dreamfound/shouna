package com.dream.shouna.data.repository

import com.dream.shouna.data.remote.ai.AiClient
import com.dream.shouna.data.remote.ai.AiRequest
import com.dream.shouna.data.remote.ai.AiScene
import com.dream.shouna.domain.ai.AiDraft
import com.dream.shouna.util.TextNormalizer
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AI 调用的**唯一闸门**（`PRD.md` §14.5 `V2-SEAM-06` / §14.6 `V2-COST-03②`；`实现约束.md` §7-5）。
 *
 * 任何 AI 调用都必须经过这里 —— 触发点集中在一处，不散落在各页面。放行顺序（任一不通过即返 null，
 * 调用方走人工回退，**不弹窗、不阻塞**）：
 *
 * 1. **必需内容**：文字 / 语音句通道要有话、照片通道要有图（缺了不必浪费一次往返）；
 * 2. **总开关**（出厂默认关）→ 关则不调用、不产生任何网络请求；
 * 3. **密钥**：读取归 [AiSettingsRepository]、传递归本闸门（[AiClient] 只拿到明文参数）；
 * 4. **进程内缓存**：键 = 场景 + 输入 + **图片指纹** + 分类名单 + 位置清单（见下），
 *    命中**直接返回草稿、不再发请求**（但用量照计，见 5）；
 * 5. **用量**：**真调用成功**与**命中缓存**都计 —— 次数 +1，token 按「该条**首次**调用回报的量」累加
 *    （失败不计，`V2-COST-04`）。
 *
 * **用量口径：命中缓存也计**（2026-10-09 用户定）。缓存条目因此要记住该条**首次调用**回报的 token
 * （[CacheEntry.tokens]），命中时次数 +1、token **再累加一次**。⇒ 设置页的「共消耗」是「AI 产出量」
 * 口径（同一结果被重放也照算），**会高于**供应商账单 —— 它用于回答「AI 用得多不多」，**不可用于对账**。
 * 首次调用没回报 `usage` 的那条，命中时**只 +1 次数**、token 不动（不凑假数）。
 *
 * **缓存键必须含场景、位置清单与图片**（2026-10-08 修正）：V2 起同一句话在不同场景、不同位置
 * 上下文下会得到不同结果 —— 只按「输入 + 分类名单」建键，会让「换了位置后重说同一句话」命中
 * 旧结果，属**静默错误**（结果看着正常，其实张冠李戴）。照片通道同理：
 * **两张不同的图若文字输入都为空**，只按输入建键必然撞键，用户会拿到上一张图的识别结果。
 * 故图片进键的是**指纹**（SHA-256 前 8 字节），而非原串 —— base64 动辄数百 KB 到数 MB，
 * 直接当键的一部分会把缓存撑成内存黑洞。
 *
 * 缓存只在**进程内存活**（LRU 上限 [CACHE_CAPACITY] 条、不落盘）：重启即失效，避免把用户输入长期
 * 留存；两个清单都先归一化再排序，避免「名单顺序变了就漏命中」。位置清单取**全量**位置树
 * （口径见 `docs/code-map/05-改动须知.md`）：全量只在**位置增删**时变化，而「跟随当前选中位置」
 * 的子集会随每次选择而变，键一变命中率就塌 —— 且子集截断会缺中间层，让模型丢掉父链。
 *
 * **不做配额**（`docs/code-map/02-数据与领域.md`-5）：BYO Key 下费用由用户与供应商直接结算，
 * 应用内再加一道限额属重复管控。
 */
@Singleton
class AiGate @Inject constructor(
    private val client: AiClient,
    private val settings: AiSettingsRepository,
) {

    private val cacheMutex = Mutex()

    /** 访问序 LRU：`accessOrder = true` + 覆写 [LinkedHashMap.removeEldestEntry]。 */
    private val cache =
        object : LinkedHashMap<String, CacheEntry>(CACHE_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CacheEntry>,
            ): Boolean = size > CACHE_CAPACITY
        }

    /**
     * 缓存条目：草稿 + 该条**首次调用**回报的 token（`null` = 那次没回报）。
     * token 随条目一起留存，是为了「命中缓存也要计用量」——命中时按同一口径再累加一次。
     */
    private data class CacheEntry(val drafts: List<AiDraft>, val tokens: Int?)

    /**
     * 一次结构化抽取。
     *
     * @return 草稿**列表**（照片可识别出多件）；**不可用 / 未开启 / 无密钥 / 失败**一律 null
     *   （调用方据此走人工录入）。返回**空列表**表示调用成功但一件都没识别出 —— 与 null 不同。
     */
    suspend fun run(request: AiRequest): List<AiDraft>? {
        if (!hasRequiredInput(request)) return null
        if (!settings.isEnabled()) return null
        val apiKey = settings.apiKey() ?: return null

        val key = cacheKey(request)
        cacheMutex.withLock { cache[key] }?.let { hit ->
            // 命中缓存：不发请求，但**用量照计**（次数 +1、token 按该条首次调用量再累加，见类注释 4/5）。
            settings.countUsage(hit.tokens)
            return hit.drafts
        }

        // token 用量由 client **顺带回报**（见 `AiClient.parse` 的 `onUsage`）。回调在 `parse` 返回前
        // 同步触发 ⇒ 这里捕获到的一定是**本次**调用的值。供应商没回报 ⇒ 保持 null ⇒ 只计次数。
        var usedTokens: Int? = null
        val drafts = client.parse(request, apiKey) { usedTokens = it } ?: return null

        // 条目连 token 一起存：命中时要按同一口径再计一次（类注释 5）。
        cacheMutex.withLock { cache[key] = CacheEntry(drafts, usedTokens) }
        // 次数与 token **同一处、同一口径**：成功才计（失败的调用在上面那行 `?: return null` 已返回）。
        settings.countUsage(usedTokens)
        return drafts
    }

    /** 场景必需内容是否齐备：照片通道看图、其余看文字。 */
    private fun hasRequiredInput(request: AiRequest): Boolean = when (request.scene) {
        AiScene.PHOTO_INPUT -> !request.imageBase64.isNullOrBlank()
        // 【2026-10-10 停用 · 注释保留】录音通道的判定：`AiScene.VOICE_AUDIO -> !request.audioBase64.isNullOrBlank()`
        AiScene.TEXT_INPUT, AiScene.VOICE_INPUT, AiScene.QUERY -> request.input.isNotBlank()
    }

    /**
     * 缓存键：**场景 + 输入 + 图片指纹 + 分类名单 + 位置清单**，缺一不可（见类注释）。
     * 两个清单都先归一化再排序 —— 名单顺序不同、写法有大小写/空白差异，都算同一次请求。
     *
     * 【为什么图片必须进键】它是「文字输入为空时唯一能区分两次请求的东西」：照片通道的 `input`
     * 恒为空（那是识别指令，不进 [AiRequest]），**不把图片指纹放进键 = 第二张图必然命中第一张
     * 的缓存**，用户会拿到上一次的识别结果，而界面看不出任何异常。
     */
    private fun cacheKey(request: AiRequest): String = buildString {
        append(request.scene.name)
        append(KEY_SEPARATOR)
        append(request.input)
        append(KEY_SEPARATOR)
        request.imageBase64?.takeIf { it.isNotBlank() }?.let {
            append(fingerprint(it))
            append(KEY_SEPARATOR)
        }
        // 【2026-10-10 停用 · 注释保留】录音通道的音频指纹（与图片同处一条理由：`input` 恒空时
        // 靠它区分两次请求）：
        // request.audioBase64?.takeIf { it.isNotBlank() }?.let {
        //     append(fingerprint(it))
        //     append(KEY_SEPARATOR)
        // }
        request.categoryNames.map { TextNormalizer.normalize(it) }.sorted().forEach {
            append(it)
            append(KEY_SEPARATOR)
        }
        // 两组清单之间再插一个分组分隔符：否则「分类组 + 位置组」的内容一旦互相重叠，会有歧义。
        append(GROUP_SEPARATOR)
        request.locationPaths.map { TextNormalizer.normalize(it) }.sorted().forEach {
            append(it)
            append(KEY_SEPARATOR)
        }
    }

    /**
     * 图片内容的短指纹（SHA-256 前 8 字节 = 16 位十六进制）。碰撞概率对 20 条 LRU 而言可忽略；
     * 摘要不可用（理论上的算法缺失）时**回落 `hashCode`** —— 宁可保住「有值」这一点，也不让键里
     * 缺项（缺项 = 两图撞键 = 静默错误）。
     */
    private fun fingerprint(base64: String): String = runCatching {
        MessageDigest.getInstance(ALGORITHM_SHA256)
            .digest(base64.toByteArray(Charsets.UTF_8))
            .take(FINGERPRINT_BYTES)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }.getOrElse { base64.hashCode().toString() }

    private companion object {
        const val CACHE_CAPACITY = 20
        const val LOAD_FACTOR = 0.75f

        const val ALGORITHM_SHA256 = "SHA-256"
        const val FINGERPRINT_BYTES = 8

        /** 键分隔符用不可见控制字符：用户输入里不会出现，不会与输入内容撞键。 */
        const val KEY_SEPARATOR = '\u0000'

        /** 清单分组分隔符（分类组 / 位置组）。 */
        const val GROUP_SEPARATOR = '\u0001'
    }
}
