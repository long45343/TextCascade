# TextCascade 轻量文本同步服务端规格

状态：函数级设计已定稿，已按审查决策台账修订  
日期：2026-08-18  
协议目标：不兼容原 ClipCascade，只做轻量、可靠、高性能的文本最新值同步

## 1. 目标与非目标

### 1.1 目标

- 仅同步文本最新值：每用户只保存一份当前文本，不保存历史。
- 无数据库：账号使用 `users.json`，文本与版本只在内存中。
- 服务端重启可恢复：客户端用无状态 token 重连，并在恢复窗口内上报 snapshot。
- 低空闲资源占用：无数据库轮询、无磁盘写入、无 WebUI、无 metrics endpoint。
- 明确边界：协议错误显式返回，慢连接被隔离或断开，绝不拖垮整个服务。
- 三端协议实现：服务端与桌面端使用 C#，Android 端使用 Kotlin；三端手写模型，由服务端契约测试约束。

### 1.2 非目标

- 不兼容原 ClipCascade 的 Spring、CSRF、JSESSIONID、STOMP 协议。
- 不支持图片、文件、剪贴板历史、离线消息队列、逐设备 ACK 状态。
- 不支持多实例分布式部署、数据库后端、管理后台或 WebUI。
- 不提供 Prometheus metrics；内部只保留轻量计数器。
- 不做 Socket.IO；使用原生 WebSocket。

## 2. 已定架构

### 2.1 运行时与进程

- 技术栈：ASP.NET Core Minimal API + Kestrel 原生 WebSocket。
- 目标框架：`net10.0`；产品版本采用 SemVer，从 `0.1.0` 开始。
- 进程模型：单进程；生产环境由 systemd 或 Windows Service 托管并负责崩溃自动重启。
- TLS：Kestrel 直接终止 TLS；不提供生产/开发模式开关，所有部署都禁止明文 HTTP 登录。
- 部署产物：框架依赖单文件；目标机必须预装对应 .NET Runtime。

### 2.2 核心对象

- `ConnectionContext`：不可变稳定属性，包括连接 ID、用户名、clientId、socket、认证信息。
- `ConnectionStateBag`：可变运行时状态，包括 lastSeen、关闭标记、发送 Channel；修改必须收敛到少数明确函数。
- `UserHub`：每个在线用户一个 hub，持有最新值、版本号、幂等队列、令牌桶与用户 Channel。
- `UserRegistry`：`ConcurrentDictionary<string, UserHub>`，不同用户天然并发。
- `LatestText`：不可变 record，包含 payload、version、来源、更新时间；更新即替换引用。

### 2.3 并发模型

1. 每个连接一个独立 `ReadLoopAsync`。
2. 读循环只负责收帧、解析、验证，然后把用户级 job 投递到 UserHub Channel。
3. 每个 UserHub 一个 `UserLoopAsync` 单消费者，串行处理该用户的 clip、连接、断开与恢复 job。
4. 广播时只序列化一次 UTF-8 字节，并把同一份字节投递到每个连接的有界发送 Channel。
5. 每个连接一个 `ConnectionSendLoopAsync`，慢连接只积压自己的队列。
6. 发送队列满立即取消该连接，不等待 drain，不补发应用层 error 或 WebSocket close frame。

## 3. 配置与用户

### 3.1 配置函数

- `CreateDefaultConfig()`：内置安全默认值。
- `LoadTomlConfig(path)`：读取可选 TOML 配置并覆盖默认值。
- `ApplyEnvironmentOverrides(config)`：环境变量覆盖敏感项与非默认部署值。
- `ValidateConfig(config)`：启动时强校验；非法值 fail-fast。
- `BuildWebHost(config)`：创建 Minimal API 应用并绑定 Kestrel。

默认配置文件示例：

```toml
[server]
bind = "0.0.0.0"
port = 8443
certificate_path = "certs/server.pfx"

[auth]
token_ttl_days = 30
token_secret_env = "TEXTCASCADE_TOKEN_SECRET"
argon2_memory_kib = 19456
argon2_iterations = 2
argon2_parallelism = 1

[limits]
max_text_bytes = 524288
max_frame_bytes = 589824
send_queue_capacity = 16
seen_id_capacity = 64
hello_timeout_seconds = 5
heartbeat_interval_seconds = 30
heartbeat_timeout_seconds = 60
snapshot_window_seconds = 3
snapshot_total_bytes = 4194304
recovery_clip_queue_capacity = 16

[rate_limit]
login_ip_per_minute = 10
login_user_per_minute = 5
max_keys = 10000
clip_burst = 10
clip_tokens_per_second = 2

[files]
users_file = "users.json"
```

规则：

- 服务端不提供 production/environment 模式开关，安全校验不因部署环境降低。
- `token_secret_env` 指向环境变量名；token secret 不写入 TOML。
- token secret 必须由环境变量提供，长度至少 32 字节；缺失或过短时启动失败。
- TLS 始终启用；`certificate_path` 必须指向服务端可用证书。
- 证书仅支持无密码格式：`.pem` / `.crt` 必须是包含叶证书与未加密私钥的 PEM bundle，`.pfx` 必须可无密码加载；带密码证书不支持，遇到需要密码的 PFX 时启动失败。
- TOML 使用宽松解析：必须以 UTF-8 读取；未知键忽略并输出 warning；重复键采用后值并输出 warning；结构或类型非法仍 fail-fast。
- `max_frame_bytes` 必须大于 `max_text_bytes`，差额留给 JSON 协议头。
- 所有容量与时间配置必须大于 0，心跳超时必须大于心跳间隔。

### 3.2 用户存储

文件：`users.json`

```json
{
  "nextTokenVersion": 3,
  "users": [
    {
      "username": "alice",
      "passwordHash": "$argon2id$...",
      "tokenVersion": 1,
      "disabled": false
    }
  ]
}
```

函数：

- `LoadUsers(path)`：启动时全量读取。
- `ValidateUsers(users)`：校验 `nextTokenVersion` 必填且大于所有用户 `tokenVersion`；校验用户名唯一、哈希格式、正数 `long` tokenVersion 与 disabled 字段。
- `BuildUserLookup(users)`：构造只读用户查找表。

说明：

- 不热加载用户文件，避免在线连接认证状态与文件状态竞态。
- `tokenVersion` 不是软件版本，而是账号 token 作废计数器。
- `tokenVersion` 与 `nextTokenVersion` 使用有符号 64 位整数（`long`），只允许正数，创建与递增时溢出即 fail-fast。
- `nextTokenVersion` 是全局水位；新增用户取当前水位作为 `tokenVersion`，随后水位加一。
- `revoke-tokens` 将目标用户 `tokenVersion` 更新为当前水位，随后水位加一，保证未来新建任何账号都不会复用已撤销版本。
- CLI 写入用户文件前必须先通过 `ValidateUsers(users)`；水位递增溢出或校验失败时放弃替换并保留原文件。
- CLI 使用 PID 锁文件实现单实例：同一时刻只允许一个 TextCascade CLI 进程运行；检测到仍存活的其他 CLI 进程时，新实例直接失败退出。
- PID 锁文件覆盖 CLI 生命周期；实现必须识别并回收陈旧 PID、进程已退出但锁文件残留的情况，并在 Windows 与 Linux 上行为一致。
- 支持直接删除用户条目，不保留墓碑；之后重建同名用户时从全局水位取新 `tokenVersion`，因此不会落入旧 token 的版本空档。
- 删除或修改用户文件后需重启服务生效；重启后已删除用户不存在，其 token 因用户查找失败而失效。

### 3.3 用户 CLI

入口在同一服务端可执行文件中，不提供 WebUI。

```bash
TextCascade.Server user add --username alice
TextCascade.Server user passwd --username alice
TextCascade.Server user disable --username alice
TextCascade.Server user enable --username alice
TextCascade.Server user delete --username alice
TextCascade.Server user revoke-tokens --username alice
TextCascade.Server user list
TextCascade.Server user hash
```

函数：

- `RunCli(args)`：识别 `user` 子命令。
- `CommandAddUser()`：生成 Argon2id 哈希，从全局水位分配 `tokenVersion`，并原子重写用户文件。
- `CommandDeleteUser()`：直接删除用户条目并原子重写文件，不写入墓碑。
- `CommandHashPassword()`：只输出密码哈希。
- `CommandListUsers()`：只输出用户名、禁用状态与 tokenVersion，不输出哈希。

CLI 写入 `users.json` 时先持有 PID 单实例锁，再使用临时文件加原子替换；服务端运行中修改文件不会热生效，需重启。服务端不写入用户文件。

## 4. HTTP API

### 4.1 登录

```http
POST /api/v1/login
Content-Type: application/json
```

请求：

```json
{
  "username": "alice",
  "password": "raw-password"
}
```

函数：

- `MapLoginEndpoint()`：薄 Endpoint，只处理 HTTP 请求与响应。
- `AuthService.LoginAsync()`：执行认证、tokenVersion 校验、token 签发。
- `ParseLoginRequest()`：限制请求体 16KB、JSON 深度 3。
- `AuthenticateUser()`：Argon2id 常数时间校验。
- `CreateLoginFailure()`：统一返回 `invalid_credentials`。
- `CreateRateLimitResult()`：统一返回 `429`。

成功：

```json
{
  "token": "<compact-token>",
  "expiresAtUtc": "2026-09-17T00:00:00Z",
  "protocolVersion": 1,
  "maxTextBytes": 524288,
  "helloTimeoutSeconds": 5,
  "heartbeatIntervalSeconds": 30,
  "heartbeatTimeoutSeconds": 60
}
```

失败：

```http
401 Unauthorized
```

```json
{
  "error": "invalid_credentials",
  "message": "Invalid username or password."
}
```

规则：

- 客户端通过 TLS 发送原始密码；客户端不做 Argon2id。
- 用户不存在与密码错误返回相同错误，避免枚举用户。
- Argon2id 参数变化时，登录路径只调用 `NeedsRehash()` 输出结构化 warning，不重写 `users.json`；用户通过 CLI `passwd` 设置新密码时才生成当前参数的哈希。
- 登录限流命中返回 `429 Too Many Requests`，错误码 `rate_limited`。

### 4.2 Token

格式：

```text
base64url(payload).base64url(hmac-sha256(payload, secret))
```

payload：

```json
{
  "sub": "alice",
  "ver": 1,
  "iat": 1760000000,
  "exp": 1762592000
}
```

Token JSON 规则：

- 服务端签发时按 `sub`、`ver`、`iat`、`exp` 固定字段序输出最小化 UTF-8 JSON。
- 验证时字段顺序无关，但拒绝重复字段与未知字段。
- `sub` 是非空用户名；`ver`、`iat`、`exp` 均为有符号 64 位整数范围内的正整数；`exp` 必须大于 `iat`。
- 数字不得以小数、指数或字符串形式表示。

函数：

- `CreateTokenPayload(user, now, ttl)`：生成 `sub`、`ver`、`iat`、`exp`。
- `SignToken(payload, secret)`：HMAC-SHA256。
- `VerifyToken(compact, secret, now, userLookup)`：验签、验过期、验用户存在、验 tokenVersion。

规则：

- HMAC 比较必须常数时间。
- token 默认 30 天，可由配置调整。
- token 无服务端状态，服务端重启后仍可验证。
- 用户被禁用、删除或 tokenVersion 变化后，重启服务即可拒绝旧 token；删除后重建同名用户会从全局水位分配更高 tokenVersion。

### 4.3 登录限流

函数：

- `TryConsumeLoginLimit(ip, username, now)`：进程内滑动窗口。
- `ResetUserLoginLimit(username)`：仅在认证成功后清空该用户名窗口。

策略：

- IP 与用户名双维度限流，任一超限即拒绝。
- 默认每 IP 每分钟 10 次，每用户名每分钟 5 次。
- 用户名维度统计所有登录请求，无论认证成功或失败；认证成功后清空该用户名窗口。
- IP 维度统计所有登录请求；认证成功不清空 IP 窗口。
- 限流器设置最大 key 数，提供确定内存上限；达到上限时先清理全部过期项，仍满则拒绝新 key 的登录请求并返回 `429 rate_limited`。
- 未达到上限时只保存窗口内时间戳，过期项在该 key 被访问时惰性清理；已有 key 的请求不创建新条目。
- 单实例部署下不做分布式限流。
- 已知取舍：持有正确密码的攻击者可通过高频成功登录占满目标用户名窗口；v1 接受该风险，以换取更简单的计数与重置规则。

### 4.4 健康检查

```http
GET /health
```

函数：

- `MapHealth()`：进程能响应即返回 `200 OK`。

返回：

```json
{
  "status": "ok"
}
```

不暴露连接数、内存、用户数等内部统计。

## 5. WebSocket 协议

### 5.1 连接建立

```http
GET /api/v1/sync
Authorization: Bearer <token>
Sec-WebSocket-Protocol: textcascade.v1
Upgrade: websocket
```

函数：

- `AuthenticateUpgradeRequest(httpContext)`：升级前验 token。
- `SelectSubProtocol(requestProtocols)`：只接受 `textcascade.v1`。
- `AcceptAuthenticatedSocket(httpContext)`：认证与版本都合法才升级。

规则：

- token 放 Authorization header，不进 URL。
- token 无效、过期、用户禁用或 tokenVersion 不匹配时，不升级 WebSocket，直接返回 `401`。
- 子协议不匹配返回 `400`。
- 认证成功后，客户端必须在 `hello_timeout_seconds` 内发送 hello，用于注册设备与上报 snapshot；默认 5 秒。
- hello 通过验证前，连接不进入广播列表；该超时由服务端统一计时。

### 5.2 Client Hello

```json
{
  "type": "hello",
  "clientId": "stable-device-id",
  "clientName": "Windows-Desktop",
  "lastServerVersion": 128,
  "snapshot": {
    "payload": "...",
    "encrypted": true,
    "hash": "client-local-hash",
    "localModifiedAtUtc": "2026-08-18T08:00:00Z"
  }
}
```

函数：

- `ParseHello(frame)`：解析 hello。
- `ValidateHello(hello)`：校验 clientId、clientName、snapshot 与版本字段。
- `CreateConnectionContext(socket, user, hello)`：创建不可变连接上下文。

字段：

- `clientId`：稳定设备 ID，长度 1-128。
- `clientName`：可选，长度 0-128。
- `lastServerVersion`：客户端见过的最后服务端版本；未知为 0。
- `snapshot`：可选。仅在进程启动后的全局恢复窗口内用于选举最新值；恢复窗口结束后只执行完整协议校验，校验通过即丢弃，不写入最新值。clip 是唯一文本写入路径。

### 5.3 Server Welcome

```json
{
  "type": "welcome",
  "protocolVersion": 1,
  "latest": {
    "version": 128,
    "payload": "...",
    "encrypted": true,
    "hash": "...",
    "fromClientId": "android-a",
    "updatedAtUtc": "2026-08-18T07:59:58Z"
  }
}
```

函数：

- `CreateWelcome(latest)`：构造欢迎消息。
- `SerializeMessage(message)`：System.Text.Json 序列化为 UTF-8。

规则：

- 服务端内存无最新值时 `latest` 为 `null`。
- 恢复窗口内可先等待 snapshot 选举，再发送 welcome。
- 客户端收到相同 hash 或相同版本时可本地去重，不写剪贴板；hash 只用于本地剪贴板去重，服务端新旧值以版本为准。

### 5.4 发布文本

客户端：

```json
{
  "type": "clip",
  "id": "client-generated-unique-id",
  "payload": "...",
  "encrypted": true,
  "hash": "..."
}
```

服务端广播给同用户除发送方连接外的其他在线连接：

```json
{
  "type": "clip",
  "version": 129,
  "id": "client-generated-unique-id",
  "payload": "...",
  "encrypted": true,
  "hash": "...",
  "fromClientId": "windows-a",
  "fromClientName": "Windows-Desktop",
  "updatedAtUtc": "2026-08-18T08:01:00Z"
}
```

发送方收到 ACK：

```json
{
  "type": "clip_ack",
  "id": "client-generated-unique-id",
  "version": 129,
  "updatedAtUtc": "2026-08-18T08:01:00Z"
}
```

函数：

- `ValidateClipMessage(message)`：单函数完整验证，内部按结构、语义、资源顺序早拒绝。
- `CheckFrameSize(frameLength, config)`：WebSocket 完整帧字节数硬限制。
- `CheckPayloadSize(payloadUtf8Length, config)`：文本字段独立限额。
- `UserHub.TryDuplicate(id)`：用户级环形队列去重。
- `RememberId(id)`：记录最近消息 ID。
- `TryAcquireClipToken(now)`：用户级令牌桶。
- `NextVersion(current)`：服务端权威 `ulong` 自增；溢出抛 fatal。
- `WithVersion(latest, next)`：构造新的不可变 LatestText。
- `BroadcastAsync(userHub, latest)`：一次序列化、多连接投递。

规则：

- 客户端不携带版本号；版本由服务端按用户处理顺序生成。
- `id` 重复时不生成新版本，先返回原 ACK，且不消耗用户级令牌桶；重复 ACK 仍必须进入发送方的有界发送队列，队列满时按慢连接取消。
- 空文本、非法 UTF-8、结构缺字段、超帧、超文本、限流超限均拒绝。
- `payload` 对服务端 opaque；`encrypted=true` 时服务端不解析内容。
- 发送队列容量按消息条数计算，默认 16；队列满立即取消连接，不补发 error 或 close frame。
- 慢设备延迟到达的旧 clip 仍会获得新版本并覆盖最新值；这是最新值语义的预期行为，客户端需自行处理可能的回滚。

### 5.5 心跳

服务端定时发送应用层 JSON ping：

```json
{
  "type": "ping",
  "serverTimeUtc": "2026-08-18T08:02:00Z"
}
```

客户端必须返回：

```json
{
  "type": "pong",
  "clientTimeUtc": "2026-08-18T08:02:00Z"
}
```

函数：

- `StartHeartbeatTimer()`：统一扫描所有连接。
- `SendPing(connection)`：发送 ping。
- `MarkPongReceived(connection, now)`：更新 lastSeen。
- `CloseExpiredConnections()`：超时未收到 pong 则取消连接。

说明：

- 心跳使用应用层 JSON 消息，便于服务端记录 pong 时间并在三端保持一致行为。
- 默认 30 秒发送一次，60 秒未收到 pong 判定死亡。
- 统一扫描器代替每连接独立 timer，降低空闲调度开销。
- 统一扫描器固定每 1 秒扫描一次；hello 与心跳超时允许 0-1 秒的额外检测延迟，不提供独立配置项。

### 5.6 错误

```json
{
  "type": "error",
  "code": "text_too_large",
  "message": "Text exceeds maxTextBytes.",
  "referenceId": "client-generated-unique-id"
}
```

函数：

- `ParseResult<T>`：成功或错误显式返回。
- `CreateProtocolError(code, message, referenceId)`：构造错误。
- `SendProtocolErrorAsync(connection, error)`：发送可继续错误。
- `EnqueueImmediateClose(connection, reason)`：跳过 error 与 close frame，直接进入统一取消路径；仅用于发送队列满等无法安全写入的场景。

错误码：

| code | 含义 | 连接处理 |
|---|---|---|
| `invalid_message` | JSON 结构或字段非法 | 可继续 |
| `text_too_large` | 文本字段超限 | 可继续 |
| `frame_too_large` | 完整帧超限 | 关闭 1009 |
| `empty_text` | 空文本 | 可继续 |
| `rate_limited` | 用户级发送限流 | 可继续 |
| `hello_timeout` | 未按时发送 hello | 先发 error，关闭 1008 |
| `server_busy` | 发送队列拥塞 | 立即取消；该错误不保证发送 |

错误处理顺序：

- 需要关闭的错误必须先发送对应应用层 error 帧，再执行 WebSocket close；同一连接同类错误只触发一次关闭流程。
- 慢连接发送队列满时不补发应用层 error，也不写 close frame，直接进入取消路径；`server_busy` 语义对客户端不可靠，客户端应靠重连兜底。

可预期协议错误走 Result；不可预期异常仍由顶层兜底并进入统一清理。

### 5.7 关闭与清理

函数：

- `CancelConnection(connection, reason)`：唯一取消入口，触发 CancellationTokenSource。
- `FinallyCloseConnection(connection)`：统一关闭 socket、停止任务、从 UserHub 摘除。
- `RemoveEmptyHub(userRegistry, userHub)`：最后一个连接断开后清理空 hub；全局恢复窗口内不执行空 hub 清理，窗口收尾时仍无连接的 hub 才移除。

| close code | 含义 |
|---:|---|
| `1000` | 正常关闭 |
| `1001` | 服务端重启或维护 |
| `1008` | 策略关闭，例如 hello 超时 |
| `1009` | 帧过大 |

hello 超时先发送 `hello_timeout` error，再以 `1008` close；发送队列满则不补发 error，直接取消。`1013` 与 `4408` 不是本协议 close code，客户端不得依赖。

心跳超时、慢连接、客户端断开、协议异常都必须汇入同一 CTS 取消路径，避免重复清理和资源泄漏。

## 6. 最新值与恢复

### 6.1 正常运行

每个用户只保存一个 `LatestText`：

- `payload`
- `version`
- `hash`
- `fromClientId`
- `fromClientName`
- `updatedAtUtc`

处理顺序：

1. 读循环收帧并检查帧大小。
2. JSON 解析与 `ValidateClipMessage`。
3. 投递到用户 Channel。
4. 用户单消费者执行幂等检查与令牌桶。
5. `NextVersion` 生成新版本。
6. 不可变替换最新值。
7. 广播给除发送者外的连接，并向发送者返回 ACK。

这是最新值语义，不是可靠队列语义。离线设备不补历史，重连后只拿当前最新值。

### 6.2 服务端重启恢复

恢复窗口从服务端进程启动时间起算，结束时间为 `processStartTime + snapshot_window_seconds`。该窗口对全部用户统一生效，不按 UserHub 创建时间或首个 hello 到达时间重新计算。

函数：

- `CollectSnapshotsAsync(userHub, window)`：收集 3 秒恢复窗口内的 snapshot。
- `SelectSnapshotWinner(candidates)`：按确定性规则选举。
- `RestoreLatestText(userHub, winner)`：恢复最新值与版本基准。

选举规则：

1. `lastServerVersion=0` 的 snapshot 不参与选举；只过滤出正版本候选。
2. 若没有正版本候选，恢复结果为空，不下发最新值。
3. 在正版本候选中优先选择 `lastServerVersion` 最大者。
4. 若版本相同，选择 `localModifiedAtUtc` 最新者。
5. 若仍相同，选择 `clientId` 字典序更大者，保证结果确定。

恢复规则：

- winner 的 `LatestText.version` 使用其正版本 `lastServerVersion`，不额外加一；恢复版本不额外设置上限，`NextVersion` 溢出 fatal 保留为理论兜底。
- 无正版本候选时恢复为空；下一条服务端 clip 版本为 1。
- 恢复窗口内只收集 snapshot；合法 clip 不参与选举，进入独立有界恢复队列。
- 每用户 snapshot 预算只统计候选 `snapshot.payload` 的 UTF-8 字节数总和；上限为 `snapshot_total_bytes`，达到上限后拒绝新的 snapshot 并保持已有候选不变。元数据开销不占用该预算，由在线连接数量约束。
- 恢复队列容量为 `recovery_clip_queue_capacity`；队列满时关闭相应连接，避免内存无界增长；连接断开则丢弃其已排队 clip。
- 恢复窗口结束后，先根据 snapshot 选举 winner 并恢复最新值，再按到达顺序串行处理恢复队列中的 clip。
- 窗口结束后广播 welcome 或恢复后的最新值，客户端按 hash 与版本去重。
- 错过窗口的慢设备之后仍可发送 clip；该 clip 按到达顺序获得新版本并覆盖当前最新值。最后写入者胜，服务端不尝试识别或拒绝“逻辑上更旧”的 clip。

服务端重启后的完整链路：

1. 服务端停机前广播 `bye` 并以 `1001` 关闭连接。
2. 客户端识别服务端维护，使用无状态 token 直接重试 WebSocket。
3. 服务端重启后 token secret 与 tokenVersion 未变，token 仍可验证。
4. 客户端 hello 上报 snapshot。
5. 3 秒窗口选举 winner。
6. 服务端恢复最新值并继续同步。

### 6.3 慢连接

每个连接有独立有界发送 Channel：

- 默认容量 16 条消息。
- `TryWrite` 失败即判定慢连接。
- 立即调用 `CancelConnection`，不等待 drain，不补发应用层 error，也不写 close frame。
- 发送循环观测取消后直接退出；`OperationCanceledException` 与非取消异常都汇入统一清理路径。
- 该场景使用 abort/dispose 释放底层 socket，不执行 graceful WebSocket close 握手。
- 客户端重连后通过 welcome 拿最新值，不补发中间消息。

慢连接不能阻塞用户单消费者，也不能影响同用户其他连接。

## 7. 优雅停机

函数：

- `BroadcastByeAsync(reason)`：向所有连接发送 `bye`。
- `ShutdownAsync(CancellationToken)`：关闭连接、停止任务、等待短暂收尾。

流程：

1. 收到 SIGTERM、Ctrl+C 或服务停止请求。
2. 停止接受新连接。
3. 广播：

```json
{
  "type": "bye",
  "reason": "server_shutdown"
}
```

4. 以 close code `1001` 关闭所有连接。
5. 等待最多 2 秒，让 close frame 尽量发出。
6. 取消所有连接 CTS。
7. 清理 UserHub 与后台任务。
8. 进程退出，由系统服务管理器重启。

## 8. 日志与安全

### 8.1 结构化日志

函数：

- `LogSecurityEvent()`：记录登录、认证失败、限流与禁用用户事件。
- `RedactSensitive(value)`：统一脱敏。

规则：

- 使用 `ILogger` 结构化字段。
- 密码绝不记录。
- token 只可记录短前缀，默认不记录。
- clip payload 与 hash 不记录；clip 事件只记 version、字节数、encrypted、来源设备。
- Authorization header 不进入访问日志。

关键事件：

| 事件 | 字段 |
|---|---|
| login | username, ip, success, reason |
| connect | username, clientId, connectionId |
| disconnect | username, clientId, reason, durationMs |
| clip | username, version, bytes, fromClientId, encrypted |
| reject | username, code, bytes |
| server_stop | reason, activeConnections |

### 8.2 传输与输入安全

- 生产只允许 HTTPS/WSS。
- TLS 最低 1.2，推荐 1.3。
- 不启用 CORS。
- 不设置 Cookie，无 CSRF 面。
- 登录请求体上限 16KB。
- WebSocket 完整帧与文本字段分别限额。
- JSON 深度限制为 3。
- 协议消息只接受契约定义字段；重复字段与未知字段拒绝。若未来新增可选字段，必须提升或明确协议兼容策略。

## 9. 性能目标

| 指标 | v1 目标 |
|---|---:|
| 基础进程内存 | < 50 MB |
| 100 个空闲连接内存增量 | < 20 MB |
| 1KB 文本 LAN 广播 p95 | < 30 ms |
| 512KB 文本 LAN 广播 p95 | < 250 ms |
| 空闲 CPU | 接近 0%，心跳扫描除外 |
| 冷启动时间 | < 2 s |
| 服务端重启恢复窗口 | 3 s |

设计依据：

- 无数据库连接池与定时磁盘 IO。
- 每用户一个 Channel 单消费者，避免锁与异步持锁。
- 每次广播只做一次 UTF-8 序列化。
- 每连接发送队列有界，内存上限可预测。
- 空闲连接只保留上下文、发送 Channel 与心跳扫描状态。

## 10. 测试计划

### 10.1 纯单元测试

重点函数：

- `HashPassword`、`VerifyPassword`、`NeedsRehash`
- `SignToken`、`VerifyToken`、tokenVersion 撤销
- Token 重复字段、未知字段、非法数字与非法范围
- 全局 `nextTokenVersion` 水位递增、删除后重建同名用户、溢出 fail-fast
- CLI PID 单实例锁：活跃进程互斥、陈旧 PID 与锁文件残留处理
- `TryConsumeLoginLimit`
- 登录限流成功重置用户名窗口但不重置 IP 窗口
- 登录限流最大 key 数：先清理过期项，仍满时拒绝新 key
- `TryAcquireClipToken`
- `ValidateClipMessage`
- `CheckFrameSize`、`CheckPayloadSize`
- `UserHub.TryDuplicate`、`RememberId`
- 重复 `id` 不消耗令牌桶，重复 ACK 仍受有界发送队列约束
- `NextVersion`、`WithVersion`
- `SelectSnapshotWinner`，包括 `lastServerVersion=0` 不参与、无正版本候选恢复为空、同版本时间与 clientId 平局

认证测试注入假哈希器，避免 Argon2id 拖慢常规单元测试。

### 10.2 CI 集成测试：内存 WebSocket

使用 `CreateSocketPair()` 建立内存连接，快速稳定覆盖：

- 登录成功与失败。
- 升级前认证失败不建立 WebSocket。
- 子协议不匹配拒绝。
- hello 超时。
- 登录后仅记录 `NeedsRehash` warning，不重写用户文件。
- 部分设备重连时的 snapshot 选举，包括 `lastServerVersion=0` 被忽略与无正版本候选恢复为空。
- 恢复窗口从进程启动时间全局起算；窗口内 clip 排队、队列容量、payload 字节预算与窗口后处理顺序。
- 恢复窗口结束后 snapshot 仅校验后丢弃。
- 两客户端同用户广播与发送方 ACK；相同 `clientId` 的其他连接仍收到广播，仅发送方连接被排除。
- 不同用户隔离。
- 幂等 ID。
- 慢连接队列满立即取消且不影响同用户其他接收方。
- 停机 bye 与 1001。
- 重启 snapshot 选举。

### 10.3 本地网络测试：真实 localhost TCP

标记：`Category=NetworkIntegration`，CI 默认跳过。

```bash
dotnet test --filter Category=NetworkIntegration
```

使用 `StartTestServer()` 启动完整 Kestrel，覆盖：

- TLS 证书与 WSS。
- HTTP 升级。
- 随机端口绑定。
- 真实帧分片。
- 登录、建连、发送文本、另一客户端接收。
- 服务端重启后 token 直连重连与 snapshot 恢复。

### 10.4 契约测试与压测

- 服务端维护典型 JSON 样本，约束三端协议字段与行为。
- 契约样本必须覆盖 JSON 深度 3、重复字段、未知字段、非法数字与非法 UTF-8。
- 独立 `TextCascade.Server.Benchmark` 项目执行压测，不进入生产产物。
- 压测场景包括空闲连接、1000 并发连接、小文本广播、512KB 文本广播与慢消费者。

## 11. 客户端适配要求

客户端需要实现：

1. `POST /api/v1/login` 获取 token。
2. Authorization header + `textcascade.v1` 子协议建立 WebSocket。
3. 在登录响应返回的 `helloTimeoutSeconds` 内发送 hello，并携带本地 snapshot。
4. 应用层 ping/pong。
5. clip 发送、ACK、接收。
6. 保存服务端 version，重连时上报 `lastServerVersion`。
7. 收到相同 hash 或相同版本时不写剪贴板；收到更晚到达的旧 clip 仍可能覆盖本地文本。
8. `1001` 后按服务端维护重连；token 未过期时优先直接重连。
9. `401`、token 过期或 tokenVersion 失效时重新 HTTP 登录。
10. 重连退避建议：1s、2s、5s、10s、30s、60s，之后固定 60s；收到 1001 时初期退避更温和。

保留客户端原有能力：

- 本地剪贴板监听。
- hash 去重。
- 远端写入后的本地事件抑制。
- 密码派生 AES-GCM 加密。
- 密码安全保存与自动登录。

## 12. 实施里程碑

### M1：协议骨架

- 配置加载与 fail-fast 校验。
- `users.json` 与 CLI。
- 登录端点。
- HMAC token。
- WebSocket 升级认证与子协议协商。
- hello/welcome。
- 文本广播与 ACK。
- 纯单元测试与内存集成测试。

### M2：可靠性

- UserHub Channel 单消费者。
- 有界发送队列与立即取消策略。
- 幂等 ID。
- 服务端版本号与不可变最新值。
- 应用层心跳。
- 统一 CTS 清理。

### M3：恢复与真实网络

- 优雅停机 bye/1001。
- 3 秒 snapshot 恢复窗口。
- snapshot 总字节数与恢复 clip 队列容量。
- token 过期与 tokenVersion 撤销测试。
- 本地真实 TCP/TLS 集成测试。
- 重启后多端收敛测试。

### M4：生产化

- Kestrel TLS。
- 结构化日志与脱敏。
- 登录与消息限流。
- 框架依赖单文件发布。
- 独立 benchmark。
- 部署文档与 Runtime 版本校验。

## 13. 版本与发布

- 产品版本采用 SemVer 2.0.0，从 `0.1.0` 开始，写入 `TextCascade.Server.csproj` 的 `Version`。
- `protocolVersion` 只表示线协议版本，当前为 `1`，与产品版本独立演进。
- 目标框架为 `net10.0`；目标机必须预装兼容的 .NET 10 Runtime。
- 发布命令：`dotnet publish TextCascade.Server.csproj -c Release -p:PublishSingleFile=true`。
- 本地编译命令：`dotnet build TextCascade.Server.csproj -c Release`。

## 14. 已关闭问题

| 问题 | 结论 |
|---|---|
| 最大文本默认值 | 512KB |
| 最新值磁盘持久化 | 不做，保持极简 |
| 用户配置热加载 | 不做，重启生效 |
| token 生命周期 | 长期 token + tokenVersion 撤销 |
| 删除后重建用户 | 全局 nextTokenVersion 水位 |
| metrics | v1 不启用 endpoint |
| 协议包 | 三端手写，服务端契约测试约束 |
| 关闭码 | 应用层 error + 标准 close code；不发送 1013/4408 |
