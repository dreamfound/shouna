package com.dream.shouna.data.remote.ai

/**
 * 一次 AI 调用的**业务入参**（值对象）。
 *
 * 为什么打包成值对象、而不是继续逐个追加参数：`parse` 的入参此前已加过一轮（`apiKey`），随后
 * 又加了 [scene] 与 [locationPaths]；每加一项都要动接口 + 全部替身 + 全部测试。打包后**新增
 * 上下文项不再改签名**。
 *
 * 为什么 `apiKey` **不在**这里（`实现约束.md` §7 的密钥硬边界）：密钥不是业务数据。混进本对象
 * 后，任何一次「把整个 request 打出来」的动作（日志、断言失败输出）都会连带泄露密钥 —— 现有
 * 测试正是在断言请求体里**不出现** `sk-` 开头的串。密钥因此继续作为 [AiClient.parse] 的独立参数。
 *
 * @param input 用户提交的原句（`V2-FR-10` 的语音识别结果也走这里，用户不接触原始文本）。
 *   照片通道下它通常为空 —— 见 [imageBase64] 的说明。
 * @param scene 功能场景，决定 prompt 的功能段、是否附位置清单、以及是否带图
 * @param categoryNames 现有分类名单（进 prompt 做白名单；**空名单 = 本次不解析分类**）
 * @param locationPaths 附给模型的**已有位置全路径文本**（`V2-FR-12`）。**空列表 = 不输出位置段**
 *   ——拍照场景按 `V2-FR-13` 就是不带，文字场景清单为空时同样不输出该段。清单**只含路径文本**，
 *   不含物品、不含内部 id；范围取**全量**位置树（口径见 `code-map/05-改动须知.md`）。本层不收
 *   层级结构 —— 层级由 `AiPrompt` 从路径文本渲染成**缩进树**，中间节点自动补全。
 * @param imageBase64 **裸 base64**（不含 `data:` 前缀）的照片内容，**仅照片通道使用**（`V2-FR-11`）。
 *
 *   【为什么是「已编码好的串」而不是路径或字节】守 `util/PhotoStore` 立下的分层：**文件 IO 全部
 *   留在 UI 层**，网络层因此不需要 `Context`、也不碰文件系统 —— 它只负责把这段串拼成 data URL。
 *   将来换编码策略（换质量、换格式、加裁剪）也不会动到本层。
 *
 *   【为什么不是 `ByteArray`】值对象要能当**缓存键**的因子（见 `AiGate`），而 `ByteArray` 的
 *   `equals` 是引用比较、`hashCode` 也不看内容 —— 放进键里等于每次都不命中。字符串是值语义。
 * 【2026-10-10 停用 · 注释保留】曾有一个 `audioBase64` 字段（**裸 base64** 的 WAV 录音内容，
 * 供语音页「录音直送」用）—— **平台不受理音频输入**（`content` part 只认 `text` /
 * `image_url` / `file`；`file` 槽也只吃图片），该通道已停用、字段一并注释。
 * 原因与接回方式见 `data/local/audio/AudioRecorder.kt` 的文件头。
 */
data class AiRequest(
    val input: String,
    val scene: AiScene,
    val categoryNames: List<String> = emptyList(),
    val locationPaths: List<String> = emptyList(),
    val imageBase64: String? = null,
    // 【2026-10-10 停用 · 注释保留】录音通道的入参：`val audioBase64: String? = null,`
)
