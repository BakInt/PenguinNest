# Melodist Mobile ProGuard / R8 优化规则

# 包名重排与符号池压缩
-repackageclasses ''

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# Core Model（仅保留参与序列化映射的字段，允许其他模型与工具类被彻底内联优化）
-keep @kotlinx.serialization.Serializable class org.melodist.model.** {
    <fields>;
}

# Room（WorkManager 的 WorkDatabase 依赖 Room 生成的 *_Impl）
# Room.getGeneratedImplementation() 走 Class.forName(... + "_Impl").getDeclaredConstructor().newInstance()
# 反射实例化，R8 看不到任何直接调用点，会把生成的 *_Impl 无参构造当无用代码删掉。debug 不裁剪所以正常，
# release 包则启动即崩（崩溃发生在 Application.onCreate 之前，CrashGuard 抓不到）：
#   Unable to get provider androidx.startup.InitializationProvider:
#   java.lang.NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}

# Media3 & ExoPlayer（保留跨进程 Session 与 AIDL，移除对 Extractor/Decoder 的全量通配）
-keep class androidx.media3.session.MediaSession* { *; }
-keep interface androidx.media3.session.MediaSession* { *; }
-keep class androidx.media3.session.IMedia* { *; }
-keep interface androidx.media3.session.IMedia* { *; }
-dontwarn androidx.media3.**

# OkHttp & Coil
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn coil3.**

# Strip Debug/Verbose logs in Release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

-assumenosideeffects class org.melodist.api.ApiLogger {
    public static void d(...);
    public static void i(...);
}

-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

# Keep line numbers for crash analysis
-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile
