# TextCascade

[English](README.md)

轻量级原生 Android 剪贴板同步客户端，适用于 [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade) 服务端。纯 Kotlin，无第三方运行时依赖，内存占用低于 10MB。

## 与 ClipCascade 的区别
- **纯文本** — 移除图片和文件分享支持
- **仅 P2S** — 移除 P2P 模式
- **Xposed 后台剪贴板读取** — 可通过 Xposed 在后台读取剪贴板

## 架构

```
ClipboardManager ──► ClipboardSources ──► TextSyncEngine ──► StompClient ──► RawWebSocketClient
                        │                      │
                   Xposed Hook            AES-256-GCM
                 (system_server)         加解密
```

- **ClipboardSources** — 双通道监听：`ClipboardManager.OnPrimaryClipChangedListener`（前台）+ logcat 触发器（后台）
- **TextSyncEngine** — 去重（FNV1a-64）、长度限制、AES-256-GCM 加解密、指数退避重连
- **StompClient / RawWebSocketClient** — 基于原生 `java.net.Socket` / `SSLSocketFactory` 的 STOMP 1.0，零外部依赖
- **Xposed 模块** — 在 `system_server` 中 hook `ClipboardService.isDefaultIme`，实现后台剪贴板访问

## 环境要求

- Android 8.0+（API 26）
- LSPosed（支持 API 102+，用于 Xposed 模块）
- ClipCascade 服务端（P2S 模式）

## 构建

```bash
export ANDROID_HOME=/path/to/android/sdk
./gradlew assembleRelease
```

使用自己的密钥库签名：

```bash
apksigner sign --ks your-key.jks --out app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

## Xposed 模块

APK 本身即为 LSPosed 模块：

1. 在 LSPosed Manager 中启用该模块
2. 作用域设置为 `system`（hook `system_server` 中的 `ClipboardService`）
3. 重启设备

Hook `isDefaultIme()` 使其对 TextCascade 返回 `true`，从而获取后台剪贴板访问权限。

## 设置项

| 设置 | 默认值 | 说明 |
|---------|---------|-------------|
| 服务器地址 | `http://localhost:8080` | ClipCascade 服务端地址 |
| 哈希轮数 | `664937` | PBKDF2 迭代次数 |
| 加密盐 | (空) | PBKDF2 盐后缀 |
| 本地最大字节数 | `512000` | 剪贴板最大载荷 |
| 启用加密 | 开 | AES-256-GCM |
| 保存密码 | 关 | 仅存储 SHA3-512 哈希 |
| 开机自启 | 关 | 重启后自动启动 |
| 状态通知 | 关 | 断开时发送通知 |

## 许可证

GNU General Public License v3.0 — 详见 [LICENSE](LICENSE)。

TextCascade Android — ClipCascade 原生剪贴板同步客户端
Copyright (C) 2026 Manet Kirby

## 致谢

本项目在 Xposed 剪贴板访问逻辑上参考了 [Clipboard Whitelist](https://github.com/Xposed-Modules-Repo/io.github.tehcneko.clipboardwhitelist)。

本项目是基于 [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade) 的 Kotlin 原生 Android 客户端，原作者为 [Sathvik-Rao](https://github.com/Sathvik-Rao)。

两个项目均采用 GNU General Public License v3.0 许可。
