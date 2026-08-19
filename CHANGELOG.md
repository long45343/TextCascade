# Changelog

### 此更新日志全部由AI生成，仅供参考。

## [2.0.0] - 2026-08-18

v2 协议整体迁移：按照 `specs/spec.md` 完成从旧 ClipCascade 协议（STOMP/CSRF/Cookie + SHA3-512 登录哈希）到 TextCascade v2 Token 协议的改造，升级后无法兼容原有协议。

### Added

- 新协议层 `Protocol.kt`：hello/welcome/clip/clip_ack/ping/pong/bye/error 消息模型，上行紧凑 JSON 手工序列化（字节级确定），下行容错解析（未知字段/未知 type 忽略），RFC3339 UTC（Z 结尾）时间工具。
- `HttpLoginClient`：`POST /api/v1/login`（JSON：username、原始密码），仅 HTTPS；解析 token/expiresAtUtc/protocolVersion/maxTextBytes/hello 与心跳参数；401→凭据被拒、429→限流（Retry-After）、网络错误分类。
- WebSocket 握手改造：`Authorization: Bearer` + `Sec-WebSocket-Protocol: textcascade.v1`，路径 `/api/v1/sync`（由 https://host:port 派生）；400 子协议协商失败、401 会话失效分类；关闭帧解析 close code 与 reason（1001 温和退避依据）。
- `TextSyncEngine` v2 状态机：建连即发 hello（含本地剪贴板快照 snapshot）；welcome.latest 与远端 clip 按 version+hash 双去重后主线程写剪贴板并抑制下一次本地回环；clip_ack 更新 lastServerVersion；ping→立即 pong；bye 记录 reason 不影响重连决策；7 个错误码按处理表逐项处理（rate_limited 暂停发送约 1s）。
- 退避策略：常规断开 1/2/5/10/30/60（固定 60）；bye/close 1001 温和 1/2/5/10（固定 10）；welcome 重置；解锁（ACTION_USER_PRESENT）提前重连。
- Token 生命周期：重连前本地预判过期（距 expiresAtUtc 不足 60s 先 HTTP 静默重登再建连）；WebSocket 401 每会话周期单次自动重登；429 自动重登退避至少 30s；protocolVersion 高于客户端支持时不建连并提示升级（显示服务端版本号）。
- 端到端加密按双端约定调整：PBKDF2-HMAC-SHA256 盐输入改为 `username$password$salt`（PBE 密码为原始密码，JDK/Conscrypt 不允许空盐）；AES-256-GCM 载荷 nonce 生成 16 字节、解密兼容 12/16 字节、tag 128 位独立字段。
- `lastServerVersion` 持久化（无符号语义，初始 0，经回调由服务层存储）；clientId（UUID v4）与 clientName（Build.MODEL 去空格）首次生成持久化。
- 测试套件重写（104 个用例）：协议契约样本逐字节断言（镜像样本见 `ContractSamples.kt`，与服务端契约样本同源约定）、FNV-1a 64 官方向量、PBKDF2 独立参考实现交叉验证、GCM 12/16 字节 nonce 兼容、退避序列、去重/回显抑制、LoginClient 假连接（成功/401/429/网络错误/HTTP 拒绝）、引擎假传输全场景（hello 快照、welcome 应用/跳过、clip、clip_ack、ping/pong、bye、全部错误码、断线重连、1001 温和退避、解锁提前重连、token 预判重登、429 退避、加密互通、generation 重启）。

### Changed

- 包名/命名空间/应用 ID：`com.textcascade` → `com.textcascad.v2`（含 Manifest、Xposed 资源 `java_init.list` 与 hook 白名单包名）。
- 设置存储重构：新增 token（Keystore 加密）、token_expires_at_utc、last_server_version、max_text_bytes、hello/心跳参数、client_id/client_name、derived_key_b64（加密）；WebSocket URL 不再落盘，由服务器地址实时派生；取消"保存密码"立即清除保存密码但保留派生密钥与会话参数。
- 看门狗超时由登录参数 heartbeatTimeoutSeconds + 10s 派生（钳制 15s–300s，防恶意禁用）。
- 默认服务器地址改为 `https://localhosts:8443`（占位默认值）；本地最大字节数默认 512000。
- versionCode 13 -> 14，versionName 0.4.3 -> 2.0.0。

### Removed

- 旧协议组件：`StompClient`/`StompFrame`、`ClipApiClient`（CSRF/Cookie/`/server-mode`/`/max-size`/`/csrf-token` 流程）、登录路径 SHA3-512 哈希、`/clipsocket` 路径、STOMP 缓冲与相关测试。

## [0.4.3] - 2026-08-17

### Fixed

- 未登录时收到 SUBMIT_TEXT 不再遗留空转前台服务。
- 会话失效且未保存密码时，停止同步引擎并结束前台服务。
- 限制 WebSocket watchdog 半开检测超时上限，避免恶意服务端禁用检测。
- STOMP 帧解析兼容 CRLF 行结束。
- 去重与大小限制复用 UTF-8 字节，降低 2MB 消息峰值内存。
- 相同状态消息不再重复写 SharedPreferences 或重建前台通知。
- 服务销毁时非阻塞停止 logcat worker，避免主线程卡顿。

### Changed

- versionCode 12 -> 13，versionName 0.4.2 -> 0.4.3。

## [0.4.2] - 2026-08-17


### Changed

- versionCode 11 -> 12，versionName 0.4.1 -> 0.4.2。

## [0.4.1] - 2026-08-16

### Fixed

- WSS 默认证书模式下启用主机名校验（Hostname Verification），校验失败时拒绝发送升级请求与 Cookie。
- HTTP→HTTPS 登录流程收敛到最终 HTTPS origin，后续接口与 WebSocket URL（WSS）基于最终认证 origin 推导。
- 修复 HTTPS→HTTP 降级重定向下 Secure Cookie 的隔离保护，禁止在非安全请求中发送 Secure Cookie。
- 修复重定向语义，明确拒绝 POST 的 301/302/303 改变请求方法重定向，保持 307/308 的请求体重发。
- 修复会话失效状态（401/403、密码错误等）标记持久化失败时的处理流程，持久化失败时立即停止重登与恢复并提供明确错误提示。
- 统一剪贴板业务、WebSocket、分片消息和 STOMP 缓冲的 2MB 上限，并校验编码后的最终传输大小。
- 修复同步引擎、WebSocket、STOMP 连接替换和停止后的回调生命周期竞态。
- 修复 HTTP 响应无界读取、异常路径连接释放、TLS 策略不一致和跨主机重定向 Cookie 泄漏边界。
- 允许同主机 HTTP 80 与 HTTPS 443 之间的受控重定向，同时拒绝自定义端口和跨主机跳转。
- 修复 logcat 触发器 stderr 堵塞与持续失败时的指数退避重启与稳定重置。
- 登录会话改为检查结果的单事务原子提交，主界面状态刷新不再周期性解密 Cookie。
- 补齐认证 single-flight、HTTP/TLS/Cookie 边界、ClipboardSources logcat 生命周期与 STOMP 帧处理回归测试矩阵。

### Changed

- WebSocket 发送掩码改为 8192 字节分块处理。
- 登录、自动重登和保存并重连统一进入后台串行认证执行器。
- versionCode 10 -> 11，versionName 0.4.0 -> 0.4.1。

## [0.4.0] - 2026-08-16

### Fixed

- 修复 WebSocket watchdog 线程随断线重连泄漏的问题。
- 修复出站剪贴板加密配置异常可能导致进程崩溃的问题。
- 修复远端文本写入剪贴板失败可能导致主线程崩溃且去重状态不一致的问题。
- 修复部分前台服务路径未调用 startForeground 即退出，在开机或系统拉起时可能崩溃的问题。
- 修复 PING/PONG 与业务帧并发写 WebSocket 导致协议数据交错的问题。
- 修复 ClipboardSources 快速停止/启动可能残留多个 logcat 触发线程的问题。
- 修复 Keystore 暂时解密失败时误删加密会话字段的问题。
- 修复 LSPosed Hook 安装失败后 installed 状态不回滚的问题。
- 修复 WebSocket 握手响应头无大小上限的问题，并支持 RFC 6455 分片文本消息。

### Changed

- PBKDF2 哈希轮数增加输入范围校验，避免误输入极端值导致长时间 CPU 占用。
- 登录进行中会同步禁用“保存并重连”按钮。
- versionCode 9 -> 10，versionName 0.3.5 -> 0.4.0。

## [0.3.5] - 2026-08-16

### Fixed
- 修复解锁恢复与两阶段重连冲突：引入 PendingReconnectAction，防止解锁/回前台将 HTTP 阶段强制重置回旧 cookie，消除额外延迟。

## [0.3.5] - 2026-08-16

### Added
- 新增解锁恢复与两阶段重连冲突修复规格，明确阶段感知恢复、手动重连隔离、任务合并与回归测试。

### Changed
- versionCode 8 -> 9，versionName 0.3.4 -> 0.3.5。
## [0.3.4] - 2026-08-16

### Added
- 重写两阶段断线恢复实施规格，明确 cookie WebSocket 重试、缓存凭据 HTTP 重登、并发保护、测试矩阵与 LSPosed 边界。

### Changed
- versionCode 7 -> 8，versionName 0.3.3 -> 0.3.4。

## [0.3.3] - 2026-08-15

### Fixed
- App 被系统冻结/解冻后 WebSocket 断开可能无回调、不重试的问题。
- RawWebSocketClient 异常路径双重回调导致具体错误被 "socket closed" 覆盖的问题。
- TextSyncEngine stop() 后同实例无法安全重启（RejectedExecutionException）的问题。
- 断线重连等待期间状态错误显示为 "正在连接" 的问题，现在显示重试倒计时。
- 回前台时若连接已断，主动强制重连，不再被动等待退避计时。

### Changed
- versionCode 6 -> 7，versionName 0.3.2 -> 0.3.3。

### Added
- TextSyncEngine 暴露只读连接状态 isConnected / isConnecting / isStopped。
- ClipForegroundService 新增幂等的 RESUME_RECONNECT 动作。
- 新增 TextSyncEngineRecoveryTest，覆盖原始错误回调唯一性与 engine 重启。


## [0.3.2] - 2026-08-13

### Fixed
- "保存并重连"现在始终使用当前 UI 参数重新登录并重新生成 SHA3-512 / PBKDF2 密钥，不再在旧会话有效时仅重建 WebSocket。
- 保存的密码因 Keystore 解密失败时，启动会显示明确提示，不再静默清空。

### Changed
- versionCode 5 -> 6，versionName 0.3.1 -> 0.3.2。
- 标准化 Gradle wrapper（gradlew / gradlew.bat / gradle/wrapper），移除硬编码 Linux 路径，支持 Windows 与 Linux/macOS 直接构建。

### Added
- 新增 ClipForegroundServiceTest，验证"保存并重连"Intent 的密码透传。


## [0.3.1] — 2026-08-13

### Added
- GitHub Actions CI（`.github/workflows/ci.yml`）：push 到 main、PR 到 main 时自动跑 `assembleDebug` + `testDebugUnitTest`，上传 debug APK 与测试结果 artifact。
- GitHub Actions Release（`.github/workflows/release.yml`）：推送 `v*` tag 时自动构建并创建 GitHub Release，附带重命名后的 debug APK。

### Changed
- versionCode 4 → 5，versionName 0.3.0 → 0.3.1。


## [0.3.0] — 2026-08-12

### Fixed
- 登录点击后崩溃：`EncryptedPrefs.encrypt()` 在 `MainActivity.login()` 的后台线程 `onSuccess` 块中可能抛 Keystore 异常，导致进程被默认 `UncaughtExceptionHandler` 杀死。现在 `tryEncrypt()` 失败返回 null、`putSecret` 自动回退明文，外层 `try/catch` 兜底。
- `autoLogin()` 在 `serviceRunning == true` 时不调用 `startForeground`，Android 12+ 会抛 `ForegroundServiceDidNotStartInTimeException`。已改为总是调用，Android 14+ 时显式传 `FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING`。
- SHA3-512 + PBKDF2 在主线程计算导致中低端机 ANR，已移到后台线程执行。

### Added
- UI 保存密码状态指示器：勾选并保存密码后，密码输入框下方显示绿色 "● 已保存密码 — 留空即可复用"，不再仅靠易消失的 hint；复选框即时联动。
- 主界面标题显示当前版本号，便于反馈与排错。
- 项目级 `CHANGELOG.md`。

### Security
- 敏感凭据（`passwordSha3` / `cookieHeader` / `csrfToken` / `hashedPasswordBase64` / `savedPasswordHash`）经 Android Keystore + AES-256-GCM 加密落盘；存量明文首次读取时自动迁移；Keystore 不可用时降级到明文存储而非崩溃。

### Improved
- 单飞重连 + 握手 5 秒超时 + 半开 TCP 检测；会话失效（401/403）时若已保存密码则静默重登一次，否则提示重新登录。
- STOMP 帧缓冲上限 + 畸形帧丢弃 + 1.1 header 转义。
- `previousHash` / `suppressNextLocal` 跨线程同步；先发送后提交 hash。
- logcat 剪贴板触发器进程死亡后自动重启。
- 保存并重连按钮、信任所有证书选项、WebSocket 状态通知节流。
- 单元测试基线（`EncryptedPrefsTest` / `StompFrameTest` / `ClipConfigTest` / `HashUtilTest`）。

## [0.2.0] — 2026-08-12

（内部里程碑：完成全部鲁棒性加固工作项 R1-R16 与功能项 F1-F5，以及紧随其后的登录崩溃修复 F1-F6。详细条目见 `TextCascade-Android-robustness-spec.md` / `TextCascade-crash-fix-spec.md`。）

## [0.1.1] — 2026

- 初始公开版本。
- 设备解锁后自动重连。
- 中文 README 与剪贴板来源说明同步。
