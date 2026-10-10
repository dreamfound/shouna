package com.dream.shouna.di

import com.dream.shouna.data.local.asr.AsrEngine
import com.dream.shouna.data.local.asr.RoutingAsrEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 语音识别装配（`docs/code-map/02-数据与领域.md`）。
 *
 * **接口绑定的意义（降耦合）**：上层（`ui/component/VoiceCapturePad.kt`）只认 [AsrEngine]。
 * 换识别内核 —— 系统 ⇒ 内置 ⇒ 将来换 sherpa-onnx —— 只动这一行绑定，组件与两个宿主零改动。
 * 与 `AiModule` 对 `AiClient` 的做法完全一致。
 *
 * 【为什么默认是双通道】见 `RoutingAsrEngine` 的类注释：系统优先，不可用才用内置离线。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AsrModule {

    @Binds
    @Singleton
    abstract fun bindAsrEngine(impl: RoutingAsrEngine): AsrEngine
}

/**
 * 让**组件层**（`VoiceCapturePad`）能取到应用级单例 [AsrEngine]，而不必让 ViewModel 持一个
 * 自己从不调用的依赖。
 *
 * 【为什么用入口点，而不是把引擎塞进 ViewModel】本工程有一条明确口径：**Android 侧的东西
 * 留在 UI 层**（`QuickAddViewModel` 类注释自陈「本 VM 不持有 `Context`」，照片 base64 也是
 * 在 Compose 层编码好再交给 VM）。`AsrEngine` 的实现内部持有 `Context` 并操作 `AudioRecord`，
 * 把它塞进 VM 只为让 VM 原样传回去，既违背那条口径、又给 VM 添一个死字段。
 *
 * 于是「取依赖」留在**唯一消费它的那个组件**里：与「Route 层用 `hiltViewModel()` 取 VM」
 * 是同一性质的动作，只是这次取的不是 ViewModel。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AsrEngineEntryPoint {
    fun asrEngine(): AsrEngine
}
