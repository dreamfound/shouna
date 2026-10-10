import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.dream.shouna"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.dream.shouna"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        // 2026-09-30：对 release 打开 R8。
        // 起因：实测的首次导航卡顿不是页面代码问题，而是 APK 里 13.9 万方法 / 1.9 万类
        // 全部未裁剪，首次触达新代码路径时 ART 需现场加载 + 验证 + JIT
        // （实测 3.1s 主线程 CPU、RSS +11.9MB）。打开后 release 方法数降到 18.6k。
        //
        // debug 刻意保持不压缩，理由有三，均已实测：
        //   1. androidTest 变体的 R8 跟随被测 buildType，压缩后的 app APK 会裁掉
        //      测试代码依赖的 kotlin-stdlib 入口，instrumentation 启动即
        //      ClassNotFoundException: kotlin.LazyKt；给测试 APK 加
        //      -dontshrink/-dontobfuscate/-dontoptimize 也无法弥补（缺的是 app APK 的类）。
        //   2. 保留断点、真实行号与 Layout Inspector。
        //   3. debug 迭代不需要承担 R8 全量耗时。
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // F1-01：core library desugaring，使 minSdk 24 可用 java.time（TimeUtil）。
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 2026-10-10：Vosk 的 `libvosk.so` 覆盖四个 ABI（未压缩合计约 39.7 MB）。
    // 四个架构**都保留**、但在**打包时按 ABI 拆分** —— 每个安装包只背自己那一份原生库，
    // 真机（arm64-v8a / armeabi-v7a）与模拟器（x86 / x86_64）各装各的。
    //
    // 注意两点：
    //   1. 拆包**不会**复制 assets ⇒ 每个分包仍各带一份语音模型（约 42 MB），
    //      所以单包的体积是「本架构 .so + 模型」，不是二者的总和里再省一份。
    //   2. `isUniversalApk = false`：不额外产出「全架构合一」的包，避免多出一个没人装的产物。
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    sourceSets {
        // P1-01：把导出的 Room schema 目录挂进 androidTest 资产。`MigrationTestHelper`
        // 按「数据库类全名 / <version>.json」的相对路径从资产里读 v1 / v2 的结构，
        // 而 `$projectDir/schemas` 下正是 `com.dream.shouna.data.local.ShounaDatabase/` 这一层。
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// P0-01：Room schema 导出（ARCHITECTURE-P0 §6）。导出物 = app/schemas/<db>/<version>.json。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // F1-01：导航（类型安全路由 4 条）+ Hilt 注入 + 路由键序列化
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // P0-01：本地持久化（Room 5 表）+ 拼音检索（FR-20）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.pinyin4j)

    // 2026-10-10：内置离线语音识别。**只在系统识别服务不可用时回落**（国行 ROM 普遍不暴露
    // `RecognitionService`）—— 路由与绑定见 `di/AsrModule.kt`、`data/local/asr/`。
    // 模型（约 42 MB）以 assets 形式随包发布，不入仓库（根 `.gitignore` 已登记）。
    implementation(libs.vosk.android)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
