# 协议契约 · 单一事实源

本目录不再保存 `server-spec.md` 副本。协议契约的唯一权威来源是服务端仓库：

> **https://github.com/long45343/TextCascade-Server/blob/main/docs/server-spec.md**

（原始定稿 2026-08-18，commit `eeefa35`；2026-08-27 已按 v0.3.5–v0.4.0 实现修订。）

## 为什么删除本地副本

本地副本冻结在 2026-08-18 定稿版，与服务端现实漂移了一个版本线：用户文件热加载、版本号持久化、clip 幂等内容比对语义、welcome 缺省形态等均已在服务端修订，两处维护必然再次漂移。

## 客户端适配注意（v0.4.0 修订中影响本端实现的要点）

1. welcome 无最新值时 `latest` **键整体省略**（不是字面 `null`）——解析必须把"键缺失"当作无最新值。
2. clip 幂等按**内容比对**：id 复用且 payload/hash/encrypted 全同才返回原 ACK、不耗令牌桶；复用 id 但内容不同会生成新版本覆盖最新值。
3. 服务端版本**跨重启单调续增**（版本号持久化到状态文件），会出现远高于本地 `lastServerVersion` 的跳号，去重/回滚逻辑按单调不连续设计。
4. hello 的 `lastServerVersion` **必填**（未知显式发 0），缺失按 `invalid_message` 拒绝。
5. 登录响应条件性新增 `needsRehash`；`expiresAtUtc` 为含小数秒格式；畸形请求体返回 400 `invalid_request`。
6. 预 hello 阶段的连接收不到 `bye`/`1001`（重连兜底不能依赖一定收到 close code）。
7. 用户禁用/删除经热加载**即刻生效**（新登录/新升级即被拒），无需服务端重启。

服务端 spec 的 §9 性能目标已移至服务端仓库根目录 `perf.md`（含 v0.4.0 实测数据：1KB 广播 p95 3.5ms、1000 并发验证等）。本仓库 `Perf.md` 对照的 §9 目标以该文件为准。

## 同步纪律

服务端 `docs/server-spec.md` 每次变更，需在本仓库评估客户端影响并更新实现；协议不兼容变更必须提升 `protocolVersion`。
