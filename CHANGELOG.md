# Changelog

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
