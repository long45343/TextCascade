-dontwarn io.github.libxposed.**
-keep class com.textcascad.v2.XposedEntry { <init>(); *; }
-adaptresourcefilecontents META-INF/xposed/java_init.list

# R14: 保留 LSPosed 入口和 module metadata
-keep class META-INF.xposed.** { *; }

# 保留 CryptoManager 的 SHA3 自实现
-keep class com.textcascad.v2.CryptoManager { *; }
-keep class com.textcascad.v2.Sha3 { *; }

# 保留 data class 字段名（JSON 序列化需要）
-keep @interface kotlin.Metadata
-keep class com.textcascad.v2.ClipConfig { *; }
-keep class com.textcascad.v2.ServerSession { *; }
-keep class com.textcascad.v2.UserPrefs { *; }
-keep class com.textcascad.v2.CryptoMaterial { *; }
-keep class com.textcascad.v2.EncryptedPayload { *; }
-keep class com.textcascad.v2.ClipMessage { *; }
-keep class com.textcascad.v2.LoginResult { *; }
