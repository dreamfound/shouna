package com.dream.shouna.data.local.asr

/**
 * 「按住说话 → 文字」的**唯一能力接口**（`V2-FR-10` 语音录入 / `V2-FR-18` 语音输入）。
 *
 * 【为什么要这一层】在这之前，语音识别的调用点直接写在 UI 里：`ui/component/VoiceCapturePad.kt`
 * 自己 `SpeechRecognizer.createSpeechRecognizer(...)`、自己拼 `Intent`、自己实现匿名
 * `RecognitionListener`。于是「换一个识别内核」＝改组件，而组件的两个宿主（录入页弹窗、
 * 语音 AI 录入页）都得跟着动。
 *
 * 抽出本接口之后，**换引擎 = 换一行 Hilt 绑定**（`di/AsrModule.kt`），组件与宿主零改动 ——
 * 与 `AiClient` / `DeepSeekAiClient` 同一手法（见 `docs/code-map/02-数据与领域.md`）。
 *
 * 【实现已有的两条通道】见 [SystemAsrEngine]（系统识别服务）与 [VoskAsrEngine]（内置离线），
 * 由 [RoutingAsrEngine] 做「系统优先、没有就内置」的路由。要换 sherpa-onnx 之类的第三个引擎，
 * 只需新增一个实现并改绑定，**不需要**动本接口、组件或任何宿主。
 *
 * 【实现不得抛异常】起不来一律返回 `null`、运行中失败一律走 [AsrError]；调用方只有
 * 「让用户重试」一条路 —— 与 `AiClient` 一样不阻塞主流程（`V2-FR-07` 的同一取向）。
 */
interface AsrEngine {

    /**
     * 此刻这台设备上本引擎能不能用。
     *
     * 要求**同步且廉价**（会随组件重组被反复调用）。注意它只回答「引擎在不在」，
     * 不回答「这一次能不能成功」—— 后者由 [start] 的返回值表达。
     */
    fun isAvailable(): Boolean

    /**
     * 开一次识别会话。三个回调**都在主线程**触发（实现负责切回主线程）。
     *
     * 【刻意不回传「开始了吗」】调用方在按下时就自己把界面切到「正在聆听」，
     * 免得为一次几十毫秒的握手再加一个回调。
     *
     * @param onPartial 实时上屏的中间结果（可能多次、可能为空串）。
     * @param onResult 最终文字，**非空**；整段话只交付一次。
     * @param onError 失败（没听到声音 / 引擎不可用 / 被占用）。
     * @return `null` = 这一次起不来（引擎不可用 / 初始化失败 / 上一个会话还没收摊）。
     *   `suspend` 是因为内置引擎**首次**使用要把模型装载起来（数百毫秒到数秒）。
     */
    suspend fun start(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (AsrError) -> Unit,
    ): AsrSession?

    /**
     * 释放引擎持有的**长期资源**（当前只有内置引擎的模型，常驻数十 MB 内存）。
     *
     * 【当前没有任何调用方，这是「预留位」】引擎是应用级单例、随进程存活：模型重新装载要数秒，
     * 不该每次长按重来。留这个入口是为了将来做「内存紧张时卸载」或「设置页切换引擎」——
     * 那时改的是这一层的实现，调用方与接口都不动。
     */
    fun release()
}

/**
 * 一次识别会话。抬手 [stop]、不要了 [cancel]；两者都**可重复调用**（幂等），
 * 因为「系统先报错、用户随后才抬手」这种顺序在真机上司空见惯。
 */
interface AsrSession {

    /** 停止收音、**保留**已识别内容 —— 「抬手出字」走这条（不是丢弃）。 */
    fun stop()

    /** 丢弃本次，且**不要再回调**。 */
    fun cancel()
}

/**
 * 失败原因。
 *
 * 【当前 UI 只用一条文案】用户 2026-10-10 裁定不做「按错误码差异化提示」（把它列在
 * 「本轮不动」里），所以四个取值现在并不产生四句不同的话 —— 留着是为了**不再丢信息**：
 * 此前的 `onError(error: Int)` 把错误码整个丢掉，连排查都无从下手。
 */
enum class AsrError {

    /** 引擎可用、也收上了音，但没听出内容（说话太短、太吵、静音）。 */
    NO_SPEECH,

    /** 引擎起不来：这台设备没有可用的识别服务，或模型装载失败。 */
    NOT_AVAILABLE,

    /** 已经有一个会话在跑（`AudioRecord` 是独占资源）。 */
    BUSY,

    /** 其它失败（识别服务自身报错）。 */
    OTHER,
}
