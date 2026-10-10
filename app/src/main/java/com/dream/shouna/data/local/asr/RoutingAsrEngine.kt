package com.dream.shouna.data.local.asr

import javax.inject.Inject
import javax.inject.Singleton

/**
 * **双通道路由**（`AsrEngine` 的默认绑定，见 `di/AsrModule.kt`）。
 *
 * 规则一句话：**系统识别服务能用就用它；用不了才落到内置离线引擎。**
 *
 * 【为什么是这个顺序，而不是「一律用内置」】
 * - 系统通道零体积、零额外延迟，且多数机型的识别精度更好（有云端语音服务的机型会走它自己的
 *   高质量模型）；内置通道要先把约 42 MB 的模型装进 `filesDir`，冷启动首次慢好几秒。
 * - 内置通道存在的唯一理由是「系统通道不可用」——那是**设备事实**，不是运行时抖动，
 *   所以判据只看 [SystemAsrEngine.isAvailable]（它内部就是 `queryIntentServices` 是否非空），
 *   不做「系统失败一次就永久切换」之类会随状态漂移的记账。
 *
 * 【实测对照】国行 realme RMX8899（Android 16）：系统 0 个识别服务 ⇒ 走内置；
 * Pixel 8 / 模拟器：系统 2 个 ⇒ 走系统（内置模型白带在包里，这是「双通道」的既定代价）。
 *
 * 【要换成别的引擎】改 `di/AsrModule.kt` 的绑定，或在本类里换掉第二个参数 —— 也就是
 * `AsrEngine` 这一层存在的意义。
 */
@Singleton
class RoutingAsrEngine @Inject constructor(
    private val system: SystemAsrEngine,
    private val vosk: VoskAsrEngine,
) : AsrEngine {

    override fun isAvailable(): Boolean = system.isAvailable() || vosk.isAvailable()

    override suspend fun start(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (AsrError) -> Unit,
    ): AsrSession? {
        val picked = if (system.isAvailable()) system else vosk
        return picked.start(onPartial, onResult, onError)
    }

    override fun release() {
        system.release()
        vosk.release()
    }
}
