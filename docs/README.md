# TextCascade (v1) 文档与架构说明

本分支（v1）是适用于 ClipCascade 协议（STOMP / Cookie / CSRF 机制）的原生 Android 客户端。

## 架构与核心组件

```
ClipboardManager ──► ClipboardSources ──► TextSyncEngine ──► StompClient ──► RawWebSocketClient
                        │                      │
                   Xposed Hook            AES-256-GCM
                 (system_server)         加解密
```

- **ClipboardSources** - 剪贴板事件监听：双通道保障（前台系统监听 + 后台根据 Xposed 激活状态自适应启停的 logcat 触发器）。
- **TextSyncEngine** - 同步引擎：基于 FNV-1a 64 散列的 recentRemoteHashes 有界哈希池去重与回显抑制、断线退避重连、会话过期自动重登。
- **RawWebSocketClient / StompClient** - 原生 RFC 6455 WebSocket 协议解析与 STOMP 1.0 客户端实现，搭载 2 秒写超时看门狗，及时暴露半开连接。
- **TextCascadeApplication** - 基于 `libxposed:service` 的 Xposed 激活状态机（`DETECTING`、`ACTIVE`、`INACTIVE`），驱动前台服务与后台降级。
