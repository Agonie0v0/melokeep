# 架构验收与故障推演

所有测试使用固定旧版和隔离数据；真实用户音乐、凭据、机器人和外部写入不进入默认 CI。结果记录环境、commit、依赖、数据规模和时间。通过条件是功能正确、保护行为成立、资源预算没有持续泄漏。

## 1. 不变量

1. 一个正式音乐根目录同一时间只有一个有效写入服务；Go 与旧 Python 不共写。
2. Provider/Helper 不写主库和正式曲库；所有文件发布经过 files 协调器。
3. `MediaVariant` 永久描述不含 URL/cookie；`ResolvedMedia` 只在请求和任务内存中存在，下载恢复重新解析。
4. 一个文件在发布中必须有 file operation 和每个附属文件清单；无法判断归属进入 `needs_attention`，不猜测删除/覆盖。
5. DB 事务不持有网络、helper、FFmpeg、哈希、复制或用户等待。
6. worker 使用 revision/attempt CAS；取消、重试、迟到结果不能覆盖新状态。
7. scan generation 不完整或根离线时不批量标记 missing；索引游标过滤/hash/sort 不匹配返回 400，index_revision 过期返回 409。
8. 幂等防重与显式重复下载是两件事；force_new 必须显式，不能被唯一索引悄悄拦截或覆盖已有文件。
9. 外部通知只保证本地 outbox 去重；未知送达进入 `delivery_unknown`，不盲目自动重发。
10. Actor、权限和 owner 由服务端可信入口产生；浏览器、机器人文本和模型不能自报权限。

## 2. API 与协议

从 `contracts/v1` 回放：127 个运行态已核对的唯一 method/path（含 `GET /downloads/{ident}`）、旧错误状态/detail、cookie/CSRF、Track/Quality/Download/Library DTO、SSE sequence/keepalive/Last-Event-ID、Range/HEAD/206/416/If-Range。旧 `/api/files` 数组保持；新增 `/api/library/files` 分页返回 `items,next_cursor,index_revision,indexing`，游标绑定过滤/sort 和 revision，参数不匹配返回 400，revision 变化返回 409。

Provider 每源至少有搜索成功、空结果、限流、认证、失效 JSON、超时、取消 fixture。内置开放音源与插件能力契约全部有 owner、实现状态、能力声明；在线状态另列，不以匿名网络失败代表 Go 代码失败。

Helper 回放 hello 3 秒、单行 1 MiB、总 16 MiB、stderr 256 KiB、非法路径/凭据、超时、单任务退出、cancel 后进程树清理。`batch_read_tags` 用 200 文件批、部分单项失败和取消样本；写标签用单文件样本。

## 3. 状态和幂等

并发提交相同 download dedupe_key，最多一条活动任务；不同 quality/account/config 不合并。对相同 actor/operation/idempotency key 重放同 payload 返回同结果，不同 payload 返回 409。显式 force_new/intent_nonce 产生新的 Download 意图，仍受目标文件和覆盖策略保护。

在每个持久阶段前后强制退出：下载、文件操作、整理、AI tool command、outbox sending。启动恢复结果必须与 storage-and-recovery.md 矩阵一致；不得把 `running` 直接当成功或重新执行未知外部写入。下载完成与整理失败相互独立。

搜索共享任务测试 1/10/100 订阅：单个断开不能取消其他订阅，最后订阅按宽限期取消；慢订阅者超过 buffer 只关闭自身或触发明确容量终态。

## 4. 文件、媒体和安全

测试绝对路径、`..`、symlink/reparse point、大小写、保留名、跨卷、同名目标、硬链接/no-replace、读句柄、磁盘不足、备份缺失、外部修改、NAS offline。Windows 打开播放器时编辑返回 busy/可重试，Linux 旧 inode 播放仍不能让新映射错误。

测试公网 DNS rebinding、多 IP、IPv4/IPv6、HTTPS→HTTP、跨 origin redirect、敏感头清理、代理 CONNECT 目标、配置 revision 排空、Cookie 账号隔离；Navidrome/本机端点另行允许，不混入公网 policy。

所有媒体接口用流式 body；Range、Content-Range、HEAD、客户端断开、读句柄释放和取消上游有可观察测试。FFmpeg/fpcalc 子进程受信号、超时和并发上限控制。

## 5. 迁移与回滚

迁移旧 DB、配置、`.song`、续传 checkpoint、资产别名、收藏/歌单、下载、整理历史、AI 等隔离副本；重复导入不复制；秘密不进入报告；未知 pickle 类型拒绝并保留档案。旧 active 下载/整理转 paused，正在进行写操作不自动重放。

先以只读 Go 模式启动、校验外键/路径/音频抽样、禁发通知，再切写权。人为制造发布前、发布中、DB commit 后清理失败、重启和回滚；回滚必须同时恢复 DB 与音乐/历史匹配快照。没有完整快照只能声明有限回退。

## 6. 性能和资源

固定硬件和相同工作量比较整个进程树。报告冷/热启动、p50/p95、首结果、全部完成、错误率、峰值 RSS/PSS、CPU、线程/goroutine、FD、helper/FFmpeg 数量、临时文件增长。场景包括空闲、六源搜索并发 1/4/8、连接池冷/热、2/4 下载、HLS、1k/10k/100k 曲库首扫/增量/分页、标签批读取、24 小时耐久。

立项目标：空闲进程树内存下降 ≥30%，原生六源峰值下降 ≥40%，受控搜索编排 p95 下降 ≥50%，稳定下载吞吐不低于旧版 95%，100k 索引完成后的代表性分页 p95 <200ms。它们是相同功能开关和负载的目标；若只通过减少结果、关闭安全保护或省略 helper 达成，视为失败。

耐久测试预热后不得出现持续线性 RSS、goroutine、FD、连接、缓存、事件或临时文件增长。超过预算时返回明确错误/排队结果，不静默截断文件或丢任务。

## 7. 发布门槛

G5 发布需要：G0/G1 产物可追溯；G2/G3/G4 通过；Linux amd64/arm64、Windows 构建和启动；Go/前端测试、race、fuzz、Playwright、迁移/回滚、故障矩阵、24 小时耐久；内置音源与插件契约无遗漏；许可证/NOTICE/Helper 运行依赖说明齐全。

任何一个核心不变量、文件保护、权限边界、数据迁移或进程清理失败都阻止全功能发布。单个平台在线不可达可以标记 blocked/not_run，但不能掩盖协议回归失败。
