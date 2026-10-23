# Changelog

## [0.4.4] - 2026-10-23

从 v2 架构移植三档核心稳定性、安全加固与工程化功能（保持 ClipCascade 协议兼容与低于 10MB 内存基线）：

### Fixed
- **Keystore GCM IV 机制修复**：解决 Android Keystore (API 29+) 要求 GCM IV 由系统生成，原实现调用方自传 IV 抛 `InvalidAlgorithmParameterException` 导致静默降级为明文落盘的问题。改用 `cipher.iv` 并补强真机加密语义断言。
- **logcat 行匹配精准化**：收紧匹配逻辑至完整包名标记加明确拒绝语义（`isClipboardDenialLog`），避免系统任意模块打印本应用包名（如 ActivityManager 启动进程）时误拉起悬浮窗。
- **发送写超时（半开连接快速暴露）**：新增 `WriteWatchdog`（2 秒写超时看门狗），当对端意外离线且本端未收到 FIN 时，写操作停滞即关闭底层 socket 触发重连，避免剪贴板内容静默丢失。
- **回显抑制升级为有界哈希池**：将原有一致性单次旗子 `suppressNextLocal` 升级为容量为 16 的 `recentRemoteHashes` 池，彻底解决采集链路存在轮询延迟时吞掉用户下一次真实复制的缺陷。
- **测试竞态修复**：消除 `rb6` 进程生成控制测试中停止前的自由循环次数不确定竞态，全量测试稳定性大幅提升。

### Added
- **电池优化白名单引导**：主界面开屏检测是否豁免电池优化，未豁免则调起系统引导对话框（记录拒绝记忆避免重复打扰），设置页常驻状态行与手动申请入口。
- **Xposed 激活状态检测**：引入 `libxposed:service` 构建 `TextCascadeApplication` 状态机（`DETECTING`、`ACTIVE`、`INACTIVE`），前台服务根据激活状态自适应启停 READ_LOGS 降级通道。
- **安全降级提示看板**：Keystore 异常回退明文存储时，主界面状态栏实时呈现警告并在看板中予以高亮。
- **证书 SHA-256 Pinning**：`TlsFactory` 支持证书公钥 SHA-256 指纹强绑定；`ClipConfig` 与网络层全面贯通。
- **信任所有证书安全对话框**：勾选信任所有证书时弹出系统警告对话框，点击确认才生效，取消自动回弹。
- **运行状态精炼看板**：全部正常时自动收敛为单行「运行状态：正常」；广播与复制发生时大字平滑呈现，3 秒后自动平滑复位会话状态。
- **前台通知体验优化**：添加 `setOnlyAlertOnce(true)` 防止反复更新打扰，并在副标题呈现当前后台模式。
- **OEM logcat 标签扩展**：扩展订阅 `-b system` 及三星 (`SemClipboardService`)、小米 (`MiuiClipboardService`)、华为 (`HwClipboardService`) 等 OEM 标签。
- **工程与构建优化**：Release 构建启用 R8 (`minifyEnabled true` + `shrinkResources true`)；签名凭据外置至 `keystore.properties`（缺失且为 release 时 fail-fast）；`versionName` 由 git 动态派生；Robolectric 缓存重定向至工程根目录。
- **新增文档**：补充 `CONTRIBUTING.md`、`Perf.md`（双设备实测基线）与 `docs/README.md`。

### Changed
- versionCode 13 -> 14，versionName 0.4.3 -> 0.4.4。
- 单元测试套件从 151 个扩展至 189 个，新增针对 IV 往返、Logcat 过滤、WriteWatchdog、Echo 池、电池白名单、Xposed 状态机、ReadLogs 降级、证书 Pinning、确认对话框与看板决策矩阵的完整测试，全量 100% 通过。

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