# Add project specific ProGuard rules here.
# For more details, see https://developer.android.com/guide/developing/tools/proguard.html

# Keep line numbers for readable crash reports, hide the original file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 属性保留：kotlinx.serialization / Room / Kotlin 元数据依赖这些属性做运行时查找，
# 一旦被剥离，反射路径会静默失败（表现为「解析不到 KSerializer」或「找不到字段」）。
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations

# --- kotlinx.serialization ---------------------------------------------------
# navigation-compose 的类型安全路由在运行时通过 `<T>.Companion.serializer()` 取
# KSerializer，这是反射入口：R8 无法从调用点看到它，必须显式保留。
# 规则取自 kotlinx.serialization 官方 R8 指南（1.9.x）。
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# 本项目 9 条路由是固定的 data class 集合，额外整类保留以彻底消除路由解析风险；
# 代价可控（9 个小类），收益是导航不再依赖 R8 的推断正确性。
-keep class com.dream.shouna.ui.navigation.** { *; }

-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.**

# --- Room --------------------------------------------------------------------
# room-runtime 自带 consumer 规则保留 @Database 子类名（schema 校验用类全名做
# identity hash，被重命名会导致升级校验失败）。此处只补生成实现类的入口。
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# --- Hilt / Dagger -----------------------------------------------------------
# hilt-android 自带 consumer 规则（含 @HiltViewModel / @AndroidEntryPoint 的
# 保留与 Hilt_* 子类名），此处不重复 keep，避免削弱压缩。

# --- pinyin4j ----------------------------------------------------------------
# 2.5.1 全部类中只有 ResourceHelper 走资源路径（getResourceAsStream 按字符串取
# unicode_to_hanyu_pinyin.txt 等），无 Class.forName、无反射字段访问 —— 已逐类核验。
# 故不需要 keep，交给 R8 优化这条每次录入都会走的热点路径；只压掉缺失可选依赖的告警。
-dontwarn com.belerweb.**
