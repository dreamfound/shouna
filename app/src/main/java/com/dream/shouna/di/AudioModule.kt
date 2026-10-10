// ============================================================================================
// 【2026-10-10 停用 · 整体注释保留】本件属于「语音 AI 录入页录音直送」这条已回退的链路。
//
// 停用原因：DeepSeek 的 chat/completions 只受理 `text` / `image_url` / `file` 三种 content
// part —— `input_audio` 被 422 拒收（unknown variant），而 `file` 槽也只吃图片（400：格式白名单
// 为 webp / png / jpeg / gif）；官网能力表仅有 Vision，没有任何音频输入项（2026-10-10 实测两次）。
// 语音 AI 录入页已改回 `VoiceCapturePad`（说话变字、音频不出设备）。
// 口径与理由见 docs/code-map/05-改动须知.md 第 34 条。
//
// 恢复方式：删掉本段头注释，再把下面各行行首多出来的 `//`（及其后一个空格）去掉即可。
// ============================================================================================
// package com.dream.shouna.di
//
// import com.dream.shouna.data.local.audio.AudioRecorder
// import com.dream.shouna.data.local.audio.PcmWavRecorder
// import dagger.Binds
// import dagger.Module
// import dagger.hilt.EntryPoint
// import dagger.hilt.InstallIn
// import dagger.hilt.components.SingletonComponent
// import javax.inject.Singleton
//
// /**
//  * 录音装配（`docs/code-map/02-数据与领域.md`）。
//  *
//  * **接口绑定的意义（降耦合）**：上层（`ui/component/AudioCapturePad.kt`）只认 [AudioRecorder]。
//  * 换录音内核 —— PCM/WAV ⇒ 将来上压缩格式、或改采样参数 —— 只动这一行绑定，组件与宿主零改动。
//  * 与 `AsrModule` 对 `AsrEngine`、`AiModule` 对 `AiClient` 的做法完全一致。
//  *
//  * 【与 `AsrModule` 并列，不合并】两条链路自 2026-10-10 起**同时活着**：录入页那条 🎤 走
//  * `AsrEngine`（说话变字、不出设备），语音 AI 录入页走 `AudioRecorder`（录音直送模型）。
//  * 合成一个模块只会让「换掉其中一个」变成「两个一起动」。
//  */
// @Module
// @InstallIn(SingletonComponent::class)
// abstract class AudioModule {
//
//     @Binds
//     @Singleton
//     abstract fun bindAudioRecorder(impl: PcmWavRecorder): AudioRecorder
// }
//
// /**
//  * 让**组件层**（`AudioCapturePad`）能取到应用级单例 [AudioRecorder]，而不必让 ViewModel 持一个
//  * 自己从不调用的依赖 —— 理由与 `AsrEngineEntryPoint` 逐字相同（本工程口径：**Android 侧的
//  * 东西留在 UI 层**，VM 不持有 `Context`、不碰 `AudioRecord`）。
//  */
// @EntryPoint
// @InstallIn(SingletonComponent::class)
// interface AudioRecorderEntryPoint {
//     fun audioRecorder(): AudioRecorder
// }
