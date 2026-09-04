# Changelog

### 此更新日志全部由AI生成，仅供参考。

## [Unreleased]

标准化库替换第一组（零新依赖重构，行为保持；审计发现：Protocol.kt/CryptoManager 的手写 JSON 转义经查证保留——Android org.json 会把 `/` 转义成 `\/`，Base64 与剪贴板文本含 `/` 时会破坏字节级契约，原因已注释在两处源码）：

### Changed
- **恢复活动重连收紧（路径 4）**：亮屏（`SCREEN_ON`）/解锁（`USER_PRESENT`）不再触发重连，仅当收到 `ACTION_DEVICE_IDLE_MODE_CHANGED` 且 `PowerManager.isDeviceIdleMode=false`（设备退出 Doze，含维护窗口）时触发；进入 Doze 方向的广播被丢弃，不再白烧一次退避档位。连带效果：设备未进入过深度 Doze 时仅亮屏不重连（靠既有断线退避或回 App 触发）；维护窗口/退出 Doze 时机与重连时机对齐。回 App（`RESUME_RECONNECT`）路径不变。
- **登录 HTTP 层迁移 OkHttp**：删除 `HttpLoginClient` 的 HttpURLConnection 手工管线与手写限长读循环，改用 OkHttp 4.12（既有依赖）：connect/read 5s、`followRedirects(false)`（保持原 `instanceFollowRedirects=false` 语义）、trustAll/pinning 复用 `TlsFactory` 与同步传输同源装配；Retry-After 经 `response.header` 读取，限长读改 okio source（2xx 体 2MB / 错误体 64KB，超限同消息 IOException）；Content-Type 由请求体携带（ByteArray 路径不带 charset 后缀），与旧行为一致。测试缝 `connectionFactory: ((URL) -> HttpURLConnection)?` → `clientFactory: (() -> OkHttpClient)?`（internal，仿 OkHttpTransport）。
- **登录测试拆分两层**：`LoginClientTest`（Robolectric，OkHttp 拦截器短路返回预置响应，覆盖 org.json 解析路径与请求形态）+ 新增 `LoginClientHttpTest`（纯 JVM，MockWebServer 真实 HTTPS：301 不跟随、2xx 2MB/错误体 64KB 超限、401/429+Retry-After/500 映射、trustAll 与证书 pinning 经真实 TlsFactory 装配、默认客户端配置断言）。拆分原因：Robolectric 的 conscrypt 与 okhttp-tls 自定义 TrustManager 组合下 TLS 校验不可靠，socket 级测试移到纯 JVM（OkHttpTransportTest 同款模式）。
- **SyncStateStore 回显抑制池**：手写 evict-oldest 淘汰三段逻辑 → `Collections.newSetFromMap(LinkedHashMap(16, 0.75f, 插入序) + removeEldestEntry)`；保持 contains 读操作不提升最近度、写入时淘汰最旧的既有语义。
- **TlsFactory 证书指纹**：手写 `%02X` joinToString 格式化 → okio `toByteString().sha256().hex().uppercase()`（okio 随 OkHttp 传递引入）。
- **AuthenticationCoordinator.submitBlocking**：CountDownLatch + AtomicReference 手搓阻塞 Future → `doSubmit` 拆分返回真 `Future` + `future.get()`（任务异常原样抛出、被替换/取消/中断返回 null 语义保持）；顺带修复「任务尚未开始即被取消时旧实现 `await()` 永久挂起」的边缘缺陷（生产路径 replaceActive=false 不可达，resetForTests 路径可达）。
- **RuntimeStateStoreHolder**：进程级懒加载单例 getter 加 `@Synchronized`，消除无锁双构造竞态；保留 `resetForTest` 可重置能力（故未用 by lazy）。
- 测试总数 259 → 269，全量两轮通过。

## [2.3.6] - 2026-09-03

v2.3.5 实机复测发现的首复制丢失/延迟残余修复（adb + 服务端 journal 联合取证，依据 `specs/phone-first-copy-delay-decisions.md` 第八章）：

### Fixed
- **welcome 取代规则改为时间序**：原实现中重连后 welcome 应用远端内容即丢弃 pending，导致「暂存的本地复制」被更旧的服务端 latest 取代而永久丢失（journal 显示用户复制与强制重连同秒触发、随后无任何手机侧 clip）。改为桌面 `TryResendPendingAsync` 同款时间序判定——仅当「暂存之后」远端又落地过新内容（`lastRemoteAppliedAtMs > pendingStoredAtMs`）才取代；welcome 自身的应用不参与比较（结算先于 executeInbound），其余场景照常补发。
- **删除 `suppressNextLocal` 一次性旗子**（spec §7-1 遗留项，对齐桌面）：自写回显抑制完全依赖 `recentRemoteHashes` 池（hash 写前登记）。旗子在采集链路存在轮询延迟（logcat 触发器退避）时会吞掉远端应用后用户的第一次真实复制——首复制「晚一个轮询周期」的残余根因。`OutboundPayloadCodec` 判定顺序相应更新为 rate limit → echo。
- 测试 258 → 259：新增时间序取代用例（可控时钟）、翻转 welcome 旧内容不吞 pending 用例、更新限流/回显顺序断言。

### Changed
- versionCode 20 -> 21，versionName 2.3.5 -> 2.3.6。

## [2.3.5] - 2026-09-03

手机端「闲置后第一次复制延迟」专项修复（依据 `specs/phone-first-copy-delay-decisions.md`，对齐桌面端 v2.3.5 同类修复）：

### Added
- **pending 暂存与补发 (Q1)**：复制时未连接或发送失败 → 文本存入 pending（仅最新一条，新复制覆盖）并强制重连；重连成功收到 welcome 后，若未被远端更新取代则自动补发；超限/回显/限流内容不暂存。
- **恢复活动信号 → 无条件重连 (Q3)**：注册 Doze 退出广播、亮屏（SCREEN_ON）、解锁（USER_PRESENT）三信号，任一触发即 abort 旧连接无条件重连（替代原「仅退避态生效 + 固定 3s 延迟」）；内置 5s 防抖防一次解锁双重建连、30s 陈旧 CONNECTING 守卫补位 OkHttp 握手读无超时。
- **电池优化白名单引导 (Q4)**：MainActivity 检测未豁免则弹系统对话框（可拒绝，拒绝后不重复弹）；设置页新增「电池优化白名单」状态行与手动入口，未豁免时附带小米 ROM 省电策略/自启动引导文案。
- **测试补强（新增 26 个用例，总数 249 → 258）**：`OkHttpTransportTest`（MockWebServer 真实 WebSocket：升级透传、close 1001 映射、401/400 握手失败映射、帧超限、看门狗/写超时推导、终态映射全分支）、`TextSyncEnginePendingTest`（暂存/补发/被取代/再失败恢复/三类不暂存）、`ConnectionManagerAwakeTest`（四态处置、5s 防抖、陈旧守卫、STOPPED 无操作）、`MainActivityBatteryTest`（弹窗/拒绝记忆/豁免重置/手动入口）。

### Changed
- **传输层迁移 OkHttp (Q7-B)**：删除手写 RFC6455 实现（`RawWebSocketClient`/`WebSocketHandshake`/`WebSocketFrameCodec`，约 700 行易错代码），改为 `OkHttpTransport` + `SyncTransport` 抽象；新增依赖 `com.squareup.okhttp3:okhttp:4.12.0`。行为保持：401/403 → 会话失效、400 → 普通退避、EOF → 1006、close code 透传（1001 温和退避）、15s 连接超时、TLS trustAll/pinning 语义不变。
- **发送超时 (Q2)**：OkHttp `writeTimeout(2s)`，半开连接上的复制 ~2s 内暴露并触发重连（原实现无发送超时，内容静默丢失）；`readTimeout(0)` 保持（服务端 30s 应用层 JSON ping）。
- **看门狗阈值 (Q5)**：接收看门狗阈值由 `heartbeatTimeoutSeconds + 10s`（默认 70s）改为 `heartbeatIntervalSeconds + 10s`（与服务端 ping 间隔对齐，钳制 [15s, 300s] 不变）。
- versionCode 19 -> 20，versionName 2.3.0 -> 2.3.5。

### Removed
- 手写 WebSocket 协议层及其单测（`RawWebSocketClientFrameTest`/`WebSocketHandshakeTest`/`WebSocketFrameCodecTest`）；协议/加密/引擎测试全部保留并通过。

## [2.3.0] - 2026-08-27

三块架构精简（依据 `specs/three-area-refactor-spec.md`）与测试密度补强：

### Added
- **统一认证核心**：新增 `AuthManager` 与统一 `AuthResult` sealed class，前台登录、后台重登、引擎 cachedRelogin 共用同一入口与 single-flight 语义；删除 `AuthenticationWorkflow`、`CachedReloginRunner`、`LoginOutcomeReducer` 等多层结果转换；`SessionRefresher.refresh()` 直接返回 `AuthResult`。
- **引擎组件纯化**：`ConnectionManager` 改用少量显式事件回调（`onConnected`/`onInboundText`/`onClosed(ConnectionCloseInfo)`/`onSessionExpired`），删除宽接口 `ConnectionEvents`；入站 `InboundMessageDispatcher` 变为纯判定器，只产出不可变 `InboundCommands`；出站 `OutboundPayloadCodec` 返回纯结果（Ready/Suppressed/RateLimited/TooLarge 等），状态文案与连接查询统一收口到 `TextSyncEngine`。
- **测试覆盖补强**：新增 `ServiceAuthenticationControllerTest`（自动登录单飞闸门、结果分支映射、Service 销毁后的取消语义）、`ClipForegroundServiceDecisionTest`（会话失效双分支与一次性重登闩锁、cachedRelogin 三分支）、`BootReceiverTest`（开机自启四条件组合，变异测试验证断言有效性）、`TextSyncEngineTimingTest`（welcome 重置退避、旧代入站丢弃、token 临期临界值）。

### Security
- **AES-GCM nonce 收口**：加密载荷 nonce 生成由 16 字节改为 12 字节（GCM 标准 IV，`CryptoManager.NONCE_BYTES = 12`）；解密保持兼容 12/16 字节。旧版本（v2.0.0–v2.2.5）客户端因解密侧同样接受 12 字节，互通不受影响。
- **运行时状态出栈 (R1–R4)**：`statusMessage`、`connectionStatusMessage`、`backgroundStatus`、`hasSession`、`serviceRunning` 不再写入 SharedPreferences，全部改为进程内 Observable 内存状态（`RuntimeStateStore`），消除高频同步刷盘；登录/登出/会话失效改用低频 `session_active` 标记随凭据事务原子提交；`BootReceiver` 启动决策不再依赖持久化 `serviceRunning`。

## [2.2.5] - 2026-08-22

Repo-Roast 锐评处方落地与模块化深度重构：

### Added
- **纯函数 WebSocket 协议分层**：
  - 新增 WebSocketHandshake 模块，纯函数负责 RFC 6455 HTTP 101 升级请求组装与 SHA-1 签名响应校验。
  - 新增 WebSocketFrameCodec 模块，纯函数负责数据帧/控制帧编解码、掩码计算与 Continuation 分片拼接。
  - 新增 WebSocketHandshakeTest 与 WebSocketFrameCodecTest 单测套件。
- **配置与运行时状态分层**：
  - 新增 AppPreferences，专门负责 SharedPreferences 配置读写与 Keystore 加解密代理。
  - 新增 RuntimeStateStore，专门管理服务/连接/后台等瞬态状态。
  - SettingsStore 重构为 Facade 门面，无缝兼容现有调用方。

### Security
- **底层加解密日志安全治理**：
  - 清理 EncryptedPrefs.getOrCreateKey 中 6 处无门禁的 Log.i 单步排查日志。
  - 对 EncryptedPrefs.tryEncrypt 异常日志进行脱敏处理，移除敏感明文字符串长度信息。

### Changed
- versionCode 18 -> 19，versionName 2.2.0 -> 2.2.5。

## [2.2.0] - 2026-08-22

锐评架构优化与技术规范重构落地：

### Added
- **证书 SHA-256 指纹 Pinning (R1)**：TlsFactory 支持证书/公钥 SHA-256 强校验；ClipConfig、SettingsStore、HttpLoginClient、RawWebSocketClient 增加 pinnedCertSha256 读写与网络层强绑定。
- **IPC 控制器抽取 (R3)**：新建 ClipServiceController，统一收敛前台服务 6 项 IPC 操作与 Action/Extra 定义，彻底精简 ClipForegroundService 伴生对象。
- **XML 声明式布局迁移 (R4)**：新建 
es/layout/activity_main.xml，重构 MainActivityUiBinding 为标准的 LayoutInflater 与 indViewById 绑定，增加锁定证书指纹配置项。

### Changed
- **Logcat 管道与 OEM 兼容扩展 (R2)**：收紧 Logcat 订阅至 -b system，扩展三星 (SemClipboardService)、小米 (MiuiClipboardService)、华为 (HwClipboardService)、通用代理 (ClipboardManager) 等 OEM Tag 过滤；实现纯逻辑日志过滤与指数退避调度。
- versionCode 17 -> 18，versionName 2.1.5 -> 2.2.0。

## [2.1.5] - 2026-08-22

架构解耦与工程化规范重构（根据 11 项技术决策全面落地）：

### Changed
- **MainActivity 原地重构**：抽取 `MainActivityUiBinding`（负责动态 UI 构建、视图持有与表单校验）与 `MainActivityAuthController`（负责登录、注销、服务启停与状态流转），主 Activity 精简至 190 行。
- **ClipForegroundService 解耦**：抽取 `NotificationController` 与 `ServiceAuthenticationController`，解耦前台通知与自动登录调度。
- **AuthenticationDependencies**：改用构造器注入模式，彻底消除全局可变静态状态。
- **ClipConfig 拆分**：解耦为 `ServerSession`、`UserPrefs` 与 `CryptoMaterial` 三组紧凑领域数据模型。
- **Logcat 生命周期绑定**：后台剪贴板监听根据应用前台可见性自动控制暂停与唤醒。
- **默认配置与多语言**：默认服务器地址修正为清晰占位符 `https://your-server:8443`；补齐中文多语言遗漏条目。
- **构建与混淆优化**：Release 构建启用 R8 (`minifyEnabled true`)，适配完整混淆保留规则；添加规范的 `CONTRIBUTING.md`。
- versionCode 16 -> 17，versionName 2.1.0 -> 2.1.5。

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


