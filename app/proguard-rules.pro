-dontwarn io.github.libxposed.**
-keep class com.textcascade.XposedEntry { <init>(); *; }
-adaptresourcefilecontents META-INF/xposed/java_init.list

# R14: 保留 LSPosed 入口和 module metadata
-keep class META-INF.xposed.** { *; }

# 保留 CryptoManager 的 SHA3 自实现
-keep class com.textcascade.CryptoManager { *; }
-keep class com.textcascade.Sha3 { *; }

# 保留 data class 字段名（JSON 序列化需要）
-keep @interface kotlin.Metadata
-keep class com.textcascade.ClipConfig { *; }
-keep class com.textcascade.EncryptedPayload { *; }
-keep class com.textcascade.ClipMessage { *; }
-keep class com.textcascade.LoginResult { *; }
