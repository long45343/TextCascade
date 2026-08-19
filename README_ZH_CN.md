# TextCascade

[English](README.md)

轻量级原生 Android 剪贴板同步客户端，适配[TextCascade-server](https://github.com/long45343/textcascade-server)。纯 Kotlin，无第三方运行时依赖。

> **注意：** 自 v2.0.0 起本客户端使用 TextCascade 协议（`POST /api/v1/login` + Bearer WebSocket，子协议 `textcascade.v1`），**不兼容 ClipCascade 服务端**。如需连接 ClipCascade服务端，请使用 [v0.4.3 版本 Release](https://github.com/long45343/TextCascade/releases/tag/v0.4.3)。

## 使用

打开app，给予后台权限、通知权限、自启权限后配置参数连接即可使用。

APK 本身即为 LSPosed 模块，在 LSPosed Manager 中启用该模块后重启即可在后台也能读取剪切板

理论上也支持ADB/shuziku给予READ_LOGS后实现后台剪切板读取，但未经测试。

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

## 与 ClipCascade 的区别
- **纯文本** - 专注于文本流转
- **新协议** - `POST /api/v1/login`（JSON，原始密码经 TLS 上送）+ Bearer WebSocket（子协议 `textcascade.v1`）；无 STOMP、无 CSRF、无 Cookie
- **Xposed 后台剪贴板读取** - 可通过 Xposed 在后台读取剪贴板，比ADB+READ_LOGS实现更稳定

## 功能特性
- **敏感设置加密存储** - 原始保存密码、派生 AES 密钥与 Bearer Token 经 Android Keystore + AES-256-GCM 加密，且TEE不可用时可回退。
- **端到端加密** - 开启加密选项后可自定义加密参数，开启后服务端无法解析出明文。

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
- [TextCascade 服务端](https://github.com/long45343/textcascade-server)

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

## 更新日志

版本历史见 [CHANGELOG.md](CHANGELOG.md)。

## 许可证

GNU General Public License v3.0 - 详见 [LICENSE](LICENSE)。

TextCascade Android - 剪贴板同步客户端
Copyright (C) 2026 Manet Kirby

## 致谢

本项目在 Xposed 剪贴板访问逻辑上参考了 [Clipboard Whitelist](https://github.com/Xposed-Modules-Repo/io.github.tehcneko.clipboardwhitelist)。

本项目在v2.0.0前基于 [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade) 的协议开发了 Kotlin 原生 Android 客户端，原作者为 [Sathvik-Rao](https://github.com/Sathvik-Rao)。

两个项目均采用 GNU General Public License v3.0 许可。
