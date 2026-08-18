# TextCascade

[English](README.md)

轻量级原生 Android 剪贴板同步客户端（v2 协议），适用于 TextCascade 服务端。纯 Kotlin，无第三方运行时依赖，内存占用低于 10MB。

## 与 ClipCascade 的区别
- **纯文本** - 移除图片和文件分享支持
- **v2 Token 协议** - `POST /api/v1/login`（JSON，原始密码经 TLS 上送）+ Bearer WebSocket（子协议 `textcascade.v1`）；无 STOMP、无 CSRF、无 Cookie
- **Xposed 后台剪贴板读取** - 可通过 Xposed 在后台读取剪贴板

## 功能特性
- **敏感设置加密存储** - 原始保存密码、派生 AES 密钥与 Bearer Token 经 Android Keystore + AES-256-GCM 加密落盘；存量明文首次读取时自动迁移，Keystore 不可用时降级为明文存储而不是崩溃。
- **v2 同步引擎** - hello（含剪贴板快照）/ welcome / clip / clip_ack / ping→pong / bye / error 全消息处理，hash+version 双去重、回显抑制、半开连接看门狗、维护退避（1/2/5/10 固定 10）与常规退避（1/2/5/10/30/60 固定 60）、解锁提前重连。
- **Token 生命周期** - 本地预判过期（距 `expiresAtUtc` 不足 60s 先 HTTP 重登）、401 每会话周期单次静默重登、429 登录限流退避至少 30s。
- **端到端加密** - PBKDF2-HMAC-SHA256（盐 = `username$password$salt`，迭代 `hashRounds`）→ AES-256-GCM 载荷 `{"nonce","ciphertext","tag"}`（16 字节 nonce）；hash 字段为 FNV-1a 64 位小写十六进制；与 Windows 端互通。
- **保存密码指示器** - 启用"保存密码"后，密码输入框下方显示绿色"已保存密码 - 留空即可复用"提示。
- **版本号标题** - 主界面显示当前应用版本，便于反馈和排错。
- **单元测试基线** - JUnit4 + Robolectric 覆盖协议契约样本（逐字节）、加密向量、登录客户端、引擎状态机与加密存储。

## 架构

```
ClipboardManager ──► ClipboardSources ──► TextSyncEngine ──► RawWebSocketClient
                        │                      │
                   Xposed Hook            AES-256-GCM
                 (system_server)         加解密
```

- **ClipboardSources** - 双通道监听：`ClipboardManager.OnPrimaryClipChangedListener`（前台）+ logcat 触发器（后台）
- **TextSyncEngine** - JSON 消息协议状态机、去重（FNV1a-64 + version）、长度限制、AES-256-GCM 加解密、退避重连、会话失效恢复
- **RawWebSocketClient** - 基于原生 `java.net.Socket` / `SSLSocketFactory` 的手写 RFC 6455 WebSocket，握手携带 `Authorization: Bearer` 与 `Sec-WebSocket-Protocol: textcascade.v1`，零外部依赖
- **Xposed 模块** - 在 `system_server` 中 hook `ClipboardService.isDefaultIme`，实现后台剪贴板访问

## 环境要求

- Android 8.0+（API 26）
- LSPosed（支持 API 102+，用于 Xposed 模块）
- 提供 `POST /api/v1/login` 与 `wss://host/api/v1/sync` 的 TextCascade 服务端

## 构建

```bash
export ANDROID_HOME=/path/to/android/sdk
./gradlew assembleRelease
```

运行单元测试：

```bash
./gradlew testDebugUnitTest
```

使用自己的密钥库签名：

```bash
apksigner sign --ks your-key.jks --out app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

## Xposed 模块

APK 本身即为 LSPosed 模块：

1. 在 LSPosed Manager 中启用该模块
2. 重启设备

## 设置项

| 设置 | 默认值 | 说明 |
|---------|---------|-------------|
| 服务器地址 | `https://localhosts:8443` | TextCascade 服务端地址（仅 HTTPS） |
| 哈希轮数 | `664937` | PBKDF2 迭代次数 |
| 加密盐 | (空) | 计入 PBKDF2 盐输入 |
| 本地最大字节数 | `512000` | 剪贴板最大载荷 |
| 启用加密 | 开 | AES-256-GCM |
| 保存密码 | 关 | 经 Android Keystore 加密保存原始密码；每次登录实时派生凭据，启用后显示保存密码指示器 |
| 信任所有证书 | 关 | 接受任意 TLS 证书（不安全，用于自签部署） |
| 开机自启 | 关 | 重启后自动启动 |
| 状态通知 | 关 | 断开时发送通知 |

## 更新日志

版本历史见 [CHANGELOG.md](CHANGELOG.md)。

## 许可证

GNU General Public License v3.0 - 详见 [LICENSE](LICENSE)。

TextCascade Android - ClipCascade 原生剪贴板同步客户端
Copyright (C) 2026 Manet Kirby

## 致谢

本项目在 Xposed 剪贴板访问逻辑上参考了 [Clipboard Whitelist](https://github.com/Xposed-Modules-Repo/io.github.tehcneko.clipboardwhitelist)。

本项目是基于 [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade) 的 Kotlin 原生 Android 客户端，原作者为 [Sathvik-Rao](https://github.com/Sathvik-Rao)。

两个项目均采用 GNU General Public License v3.0 许可。
