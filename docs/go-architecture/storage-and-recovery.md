# 数据模型、文件事务与恢复设计

状态：架构设计 v1；用于实施契约，不表示实现或耐久性测试已经通过。
基线：`c17cf18d4edb75fb3c0aa9f4be7d24e6aca87f09`，2026-11-14。
范围：Go 单实例后端的数据所有权、SQLite、文件操作、旧数据迁移与回滚。

## 1. 设计决定

1. Go 使用独立数据库 `MELOKEEP_DATA_DIR/go/melokeep.db`，不就地升级旧 Python 数据库。
2. 旧数据库和配置是迁移输入，不是运行时第二事实源；不实现双写。
3. 同一音乐根目录只允许一个版本的写入服务运行。独立 DB 不代表可共用文件写入。
4. Go 应用服务拥有业务事务；音源、标签/提取 helper 不接触数据库。
5. SQLite 事务不能原子覆盖音频文件和目录操作；使用持久意图日志协调。
6. 平台曲目、本地文件、音质变体、下载任务分别建模，不能把 URL 或路径作为永久身份。
7. 普通配置和低频扩展状态保留 JSON；需要过滤、分页、去重、恢复的字段结构化。
8. 文件修改失败保护原件；恢复存在外部变化时进入 `needs_attention`，不猜测覆盖。

本文件的独立 Go 数据库决定取代旧 go-rewrite-plan.md 中“先兼容原表”的实施路径。
保留原表语义仅指离线导入兼容，不是运行时共库。

旧实现依据：engine_api 创建 `records(kind,id,body,updated)`，逐条提交；library_pipeline 使用
`library_assets`、`library_paths`，并在 records 中保存文件操作日志。publish 备份文件、
发布音频/附属文件、更新引用后删除原路径，recover 尝试回滚 preparing/publishing。
以下方案保持这些产品能力，但重做内部持久模型；不照搬全部 Python 对象。

## 2. 身份与领域关系

### 2.1 四种核心身份

| 实体 | 身份 | 保持不变的条件 | 不能用于身份的字段 |
| --- | --- | --- | --- |
| ProviderTrack | `track_id` | 同一源的同一平台曲目 | 标题、歌手显示名、下载 URL |
| MediaVariant | `variant_id` | 同一曲目的同一质量/版本描述 | 临时签名 URL、当前文件大小 |
| Asset | `asset_id` | 同一被管理文件发生标签或路径变化 | 绝对路径、mtime、标签内容 |
| Download | `download_id` | 同一用户意图的暂停、续传、失败重试 | worker PID、attempt 编号 |

新内部 ID 使用随机 UUID 字符串；无分布式排序要求，不引入额外 ID 服务。
已迁入的 asset_id 和历史公开 ID 原样保存；旧公开 ID 通过别名表定位，不重新算 hash。
同一首歌在不同音源（如 Audius 与 FMA）是两条 ProviderTrack，不能按标题歌手自动合并为唯一录音。
版本匹配结果是带来源/置信依据的建议，不改变上述身份；跨源替代需既有版本规则认可。
没有稳定平台 ID 的站点使用适配器定义的稳定 locator；若无法证明稳定，就保存为候选，
标明需重新解析，不以标题相同推断身份相同。

MediaVariant 的 `variant_key` 是 provider 定义的稳定质量键，记录容器、编码、比特率、
采样率、位深及 preview_only；未知值保留 null，不能据扩展名宣称真正无损。
Go 模型中的 ResolvedMedia 是短期运行对象，包含 URL、有效期和传输凭据；公开 DTO 不返回秘密。
下载恢复重新 Resolve；不依赖持久化的过期 URL。需兼容的旧 URL 仅保留于迁移档案。

### 2.2 关系和边界

```text
ProviderTrack 1 ─ N MediaVariant
MediaVariant  1 ─ N Download（历史尝试可多条，活动去重由 dedupe_key 控制）
Download      1 ─ N Asset（有声节目分集或多个输出）
Asset         1 ─ N FileOperation（版本和路径变化）
LibraryJob    1 ─ N LibraryItem ─ 0..N FileOperation
Collection    1 ─ N CollectionItem ─ Track / Asset 的公开引用
```

播放列表项目支持平台引用和本地 asset 引用；Navidrome 项目保存外部服务配置 ID 与歌曲 ID，
不假装成本地文件。保持 item 顺序，不通过曲目主键强行去重，是否允许重复遵循旧接口契约。
下载所有权来自创建者，复用别人的已完成文件不转移原任务所有权；共享曲库权限与任务控制权限分开。

## 3. 持久化约定

### 3.1 通用约定

- 时间戳统一 UTC Unix 毫秒 INTEGER；文件 mtime 使用原始纳秒 INTEGER，不混用单位。
- JSON 字段必须是合法 JSON，边界 DTO 有版本，秘密引用和公开字段分别序列化。
- SQL 全部绑定参数；状态字段有 CHECK，外键逐连接启用；重要删除使用 RESTRICT。
- `revision INTEGER NOT NULL DEFAULT 1` 用于乐观并发，更新须带预期 revision。
- 软删除用于仍被历史、任务引用的 Track/Asset；到期回收另走明确清理流程。
- 主数据库位于本地可靠文件系统；不承诺把 SQLite WAL 文件放在 SMB/NFS 上安全运行。
- 音乐文件可以在 NAS 挂载目录；根目录能力探测决定可用的发布策略。

### 3.2 表定义清单

下表为逻辑 schema 合同。实现者应以 SQL migration 展开约束和外键，不由各模块自行建表。
`PK`、`FK`、`UQ` 分别表示主键、外键、唯一约束；所有 ID 均为 TEXT。

| 表 | 必需列 | 约束和索引 |
| --- | --- | --- |
| schema_migrations | version INTEGER PK, checksum, applied_at | migration 校验和不允许改写 |
| settings | namespace PK, body_json, revision, updated_at | body 有 schema_version；读写按 namespace 授权 |
| legacy_records | kind, legacy_id, body_json, source_updated, import_id | PK(kind,legacy_id)；只读迁移档案 |
| import_runs | id PK, source_fingerprint, state, report_json, created_at, completed_at | UQ(source_fingerprint)；记录完整/未完成 |
| roots | id PK, canonical_path, path_case_mode, capabilities_json, enabled, scan_generation | UQ(canonical_path) |
| provider_tracks | id PK, source, locator, title, artist, album, duration_ms, metadata_json, provider_data_json, revision, updated_at | UQ(source,locator)；index(source,updated_at,id) |
| media_variants | id PK, track_id FK, variant_key, codec, container, bitrate, sample_rate, bit_depth, preview_only, metadata_json, updated_at | UQ(track_id,variant_key)；CHECK 数字非负、bool 0/1 |
| public_refs | public_id PK, kind, target_id, quality_hint, created_at | kind=track/asset/navidrome；由服务验证引用，见下文 |
| assets | id PK, root_id FK, rel_path, path_key, status, size, mtime_ns, revision, title, artist, album, duration_ms, metadata_json, locks_json, origin_json, indexed_generation | UQ(root_id,path_key)；index(root_id,status,artist,album,id) |
| asset_aliases | alias_token PK, asset_id FK | 保存旧路径 token；不保存可执行任意路径 |
| downloads | id PK, track_id FK, variant_id FK nullable, owner_key, dedupe_key, intent_scope, state, attempt, bytes_done, bytes_total, cancel_requested, revision, error_json, settings_json, created_at, updated_at | index(state,created_at,id)；活动 dedupe 唯一索引 |
| download_outputs | download_id FK, ordinal, asset_id FK, role | PK(download_id,ordinal)；UQ(download_id,asset_id) |
| transfer_checkpoints | download_id PK/FK, part_rel_path, url_fingerprint, validator_type, validator, expected_length, durable_bytes, complete, updated_at | durable_bytes>=0；只表示已 flush 的字节 |
| library_jobs | id PK, source_download_id FK nullable, action, state, settings_json, options_json, revision, owner_key, created_at, updated_at | UQ(source_download_id) WHERE 非空；index(state,created_at,id) |
| library_items | job_id FK, ordinal, asset_id FK, state, expected_revision, before_json, proposed_json, selected_candidate_json, error_json | PK(job_id,ordinal)；index(asset_id,job_id) |
| file_operations | id PK, job_id FK nullable, item_ordinal nullable, asset_id FK nullable, kind, state, before_asset_json, after_asset_json, error_json, created_at, committed_at, expires_at | index(state,created_at,id)；index(asset_id,created_at,id) |
| operation_files | operation_id FK, ordinal, role, source_ref_json, target_ref_json, stage_ref_json, backup_ref_json, before_hash, after_hash, phase, identity_json | PK(operation_id,ordinal)；包含音频和每个附属文件 |
| collections | id PK, name, kind, owner_key, revision, created_at, updated_at | kind=playlist/favorites/play_queue；单例种类约束按兼容契约 |
| collection_items | collection_id FK, position, public_ref FK, added_at | PK(collection_id,position)；index(public_ref) |
| durable_records | namespace, id, body_json, revision, updated_at | PK(namespace,id)；低频 AI/渠道会话等，无全表业务筛选 |
| outbox | id PK, channel, recipient_key, event_key, payload_json, state, attempt, next_attempt_at, receipt_json, created_at | UQ(channel,recipient_key,event_key)；index(state,next_attempt_at,id) |
| inbox | channel, event_id, state, received_at, expires_at, result_ref | PK(channel,event_id)；重放去重，TTL 清理 |
| operation_requests | owner_key, operation, idempotency_key, request_hash, result_ref, created_at, expires_at | PK(owner_key,operation,idempotency_key) |

`public_refs` 是兼容引用解析表，kind/target_id 多态关系不能依赖普通 FK；store 层对创建、
删除实施存在性检查，integrity 检查显式查孤儿。所有强业务关联仍使用具体 FK，不能扩散为任意引用。
如果实现阶段希望改为三个 nullable FK，则必须通过合同变更，不允许某代理单方面改变所有 DTO。
`durable_records` 不替代上述任务表，不容许把下载/文件操作再序列化成不透明 JSON 整体扫描。
凭据可以继续以受权限保护的服务端配置保存；此设计不声称磁盘加密。API/日志/模型只见配置状态或引用。

活动下载唯一索引的语义：

```sql
CREATE UNIQUE INDEX downloads_active_dedupe
ON downloads(dedupe_key,intent_scope)
WHERE state IN ('queued','resolving','transferring','verifying','publishing','paused');
CREATE INDEX library_jobs_recent ON library_jobs(created_at DESC,id DESC);
CREATE INDEX assets_folder ON assets(root_id,status,path_key,id);
```

区分两层防重：HTTP/AI/Bot 传输幂等（`operation_requests` / `inbox` 阻止重传误建多条）与
业务级活动去重（`(dedupe_key, intent_scope)`）。`dedupe_key` 至少包含 source/locator、
明确音质选择和产生文件所需的配置版本；不能把不同质量或不同账号可见版本合并。
默认下载和重试使用 `intent_scope='default'`，在事务中先检查权限，再复用现有 active/`paused`
或已存在的有效 `complete` 输出（遵循旧策略）；`paused` 占用默认去重键，`cancelled`/`failed` 释放。
当调用显式传入 `force_new=true` 时必须同时提供非空 `intent_nonce`；缺少 nonce 返回 400。也可以只提供非空 `intent_nonce` 作为明确的新意图。目标路径/覆盖策略必须允许后，
设置 `intent_scope='force:' || intent_nonce`，允许同一 variant 并行或重新创建新下载意图，
仍受传输层幂等键保护，不会因网络重试产生第三条任务；发布阶段仍按既定重名策略隔离目标文件。

## 4. 写入者与事务边界

### 4.1 唯一写入路径

```text
HTTP / Bot / AI -> 应用服务 -> store.WithTx -> SQLite
                           -> 文件操作协调器 -> media/helper
扫描器 -> 索引服务 -> store（不直接调用文件发布）
```

应用服务决定授权、业务状态转换与幂等；store 提供具体 SQL 方法，不复制授权逻辑。
仓库不开放任意 SQL 给 Provider/helper；任务 worker 通过所属服务报告结果，不直接更新兄弟模块表。
文件操作协调器是音频/附属文件修改、发布、移动和恢复的唯一入口。只读播放可以并行。

SQLite 使用一个写连接、有限读连接，默认读池 2。WAL、foreign_keys=ON、busy_timeout=5000。
涉及恢复承诺的状态提交使用 synchronous=FULL；其他调优先经故障验证，不通过减弱持久性达标。
服务进程持有数据库旁实例锁，并对所有可写音乐根持有跨平台 OS 文件锁；锁内容记录版本与 PID，
但不能仅凭 stale PID 删除锁。旧 Python 不理解新锁，部署切换必须停旧服务并验证端口/进程。

### 4.2 必须在一个短事务内的操作

- 创建下载：幂等请求记录 + 下载行 + 必要的待执行标记。
- 提交文件发布：更新 asset/path alias + download_outputs + 下载成功 + 自动整理 job/items。
- 整理成功：更新资产 revision/路径/字段锁 + operation committed + item 状态 + 需要的 outbox。
- 机器人收到事件：写 inbox，再创建内部命令/待处理状态；耗时任务在事务外执行。
- 集合编辑：校验 revision，重排受影响项目，增加 revision。

AI 写工具的 operation_requests 使用 session/turn/tool-call 的稳定标识作为幂等键，保存
请求 hash 和结果引用；同键不同参数拒绝。存在 `started` 但未知结果的操作先查询实际任务/文件状态，
不能重启后直接重放。可在同一 SQLite 事务提交的写入与结果引用一起落账；外部写入使用明确状态。
低频 AI 会话/计划/等待点保存 durable_records；需要分页检索时增加明确索引，不能全表反序列化。

outbox 状态为 pending、sending、sent、retry_wait、delivery_unknown、failed、cancelled。
调用渠道前先持久化 sending；发送成功保存 receipt 与 sent。若渠道响应丢失或成功后进程崩溃，
重启进入 delivery_unknown：有渠道幂等键/查询回执则先核对，否则禁止无条件自动补发，向管理员展示
可能已送达并提供明确补发操作。只保证本地事件唯一入队，不能宣称外部通知 exactly once。
明确失败且可重试的请求按退避重试；需要人工处理的未知送达不阻塞其他消息。

HTTP、DNS、下载、ffmpeg、hash、复制、fsync、用户等待都在数据库写事务外执行。
worker 不在等待队列时持数据库事务或文件锁；锁顺序固定为任务控制锁→按 asset_id 排序的资产锁→短 DB 事务。
扫描器遇到正在文件事务中的资产跳过，稍后补扫；不要与发布操作争抢锁。
每个任务状态更新使用 `WHERE id=? AND revision=? AND state IN (...)`，影响行数为 0 时重读，
不把取消后迟到的 worker 结果写回成功。

## 5. 下载与整理状态衔接

下载状态：queued → resolving → transferring → verifying → publishing → complete。
queued/resolving/transferring/verifying 可进入 paused/cancelled/failed；publishing 必须先完成
当前文件事务提交或回滚，再呈现取消结果。不能在任意时刻 kill 后宣称文件已安全。
重试沿用 download_id，增加 attempt；旧 worker 的 attempt/revision 不匹配则拒绝其结果。
下载 `complete` 只代表输出可用，整理状态独立。下载 DTO 保留 processing 子对象兼容旧 UI。

自动整理 job 在下载文件提交的同一 DB 事务创建，唯一 source_download_id 防重复。
若自动整理未启用则不创建占位 job。下载成果属于多个文件时一次创建对应 items。
整理失败或 needs_review 不把下载改成 failed，不删除已完成音频，也不触发重新下载。
通知依据工作流阶段生成 event_key，例如 `download:<id>:delivered:<asset-revision>`，不能用轮询次数。
等待整理的机器人看到 needs_review/failed 时仍可按现有规则交付已下载文件。

LibraryJob 聚合状态由 items 推导：queued、preparing、ready、needs_review、applying、
complete、partial、failed、paused、cancelled、restored。单项状态和错误独立保存。
取消批次只阻止后续 items；当前 item 达到提交/回滚边界后停下。恢复创建新的 FileOperation，
不抹除过去日志；只允许恢复到没有后续冲突的版本。

续传 checkpoint 记录 validator 和 durable_bytes；暂停 flush/fsync 后再保存 checkpoint。
重启对磁盘长度与 checkpoint 交叉校验；多出的未确认尾部截断前先验证任务专属路径，
缺字节按实际长度保守恢复。无 validator、表示变化、206/416 不匹配时重新传输，不能盲目拼接。
进度 bytes_done 可以高于 durable_bytes，前者是 UI 进度，不是重启依据。
普通 Range、HLS 分段恢复分别定义；第一版 HLS 允许重新下载未提交任务，不伪称分段续传。

## 6. 文件操作协议

### 6.1 操作与文件清单

FileOperation.kind 为 download_publish、edit、move、derive、restore、delete。
每个 operation_files 行代表一个音频、歌词、封面或其他需要保护的附属文件。
每个引用包含 root_id、相对路径、预期文件身份，禁止 helper 返回任意绝对路径直接发布。
源文件 revision 包括 asset revision、size、mtime_ns；写入前对音频/附属文件计算或核验 hash，
高风险恢复以 hash 判断外部变化，不能只比较时间。
path_key 按根文件系统语义生成；Windows 使用适当大小写策略，不对所有 Linux 根强制 casefold。
不能用字符串前缀判断路径归属；路径逐级验证，拒绝 symlink/reparse-point 逃逸，落盘时再次验证。
TOCTOU 防护需使用平台支持的句柄/目录相对操作；不支持的根明确拒绝危险操作，不承诺纯字符串校验足够。

### 6.2 正常提交

1. 获取资产锁，重新核验预览 expected_revision、路径归属、磁盘空间和目标占用。
2. 先写 `planned` 意图及完整文件清单，再创建操作专属备份与目标同目录 stage。
3. 备份源和附属文件；校验备份 hash、flush/fsync。备份完成前不改变源。
4. helper 只修改 stage；验证格式、标签、音频不变约束；记录 after_hash。
5. 所有 stage/backup 达到平台可用的持久化要求后记录 `prepared`。
6. 记录 `publishing`，逐文件执行不覆盖发布或受控替换，并记录每个文件阶段。
7. 校验所有目标；一个短 DB 事务写资产映射、任务结果、下游 job/outbox，operation→`committed`。
8. 清理旧源、stage、临时占位，全部按文件身份/hash 保护；operation→`cleaned`。
9. 备份按期限保留；到期安全删除后标记 `backup_expired`，历史元数据继续保留。

音频和歌词/封面是多文件操作，不存在跨文件原子 rename。发布期间资产进入写锁状态，
新的读取等待短时间或返回 retryable busy；旧打开的播放句柄按 OS 行为继续，不保证无中断。
Windows 上打开媒体句柄导致 replace 被拒绝时，当前操作有限重试后暂停并保留原件/备份，
提示先停止该文件播放再重试；不强制关闭其他播放器、不无限占锁。Linux 已打开旧 inode 可继续播放，
新请求在发布事务结束后解析最新 asset 路径。磁盘不足发生在备份/stage 时也按回滚协议退出，
不得为腾空间先删原件；备份空间预估仅为预检，实际写失败仍必须处理。
同路径替换需原子 replace；新路径发布采用已验证的 no-replace 原语（例如同卷硬链接）。
不支持 no-replace 的文件系统第一版拒绝该移动/发布；不得使用“先 exists 再 rename”假装排他。
跨卷先复制到目标同目录 stage，校验后按同样协议发布，直到 DB commit 后才考虑删旧源。

### 6.3 崩溃恢复矩阵

启动恢复先于任务执行、目录监控和外部消息处理。读 API 可报告 recovering；写 API 暂拒绝。

| 最后持久状态 | 可能的文件现场 | 启动处理 | 任务结果 |
| --- | --- | --- | --- |
| planned | 未备份或部分备份，源未改 | 验证源未变，删除有明确归属的临时文件；不完整备份不用于恢复 | paused |
| prepared | 完整备份/stage，目标未改 | 校验实际现场；安全撤销 stage 或等待人工续作，不自动重放用户修改 | paused |
| publishing、DB 未 commit | 部分目标已发布、源可能原位替换 | 以清单逐项核对；有确切归属且输出未改才从备份回滚 | paused；冲突 needs_attention |
| publishing，发布动作已完成但标记未写 | stage 和目标可能为同一硬链接，或原位内容已变 | 使用 OS 文件身份、持久阶段及 hash 联合判断；证据不足保留双方 | needs_attention 或安全回滚 |
| committed | DB 已指向目标，旧源或 stage 还在 | 验证目标完整后前滚清理，绝不自动退回旧映射 | complete；清理冲突保留警告 |
| cleaned | 文件和 DB 一致，备份存在 | 正常提供服务；按到期策略处理备份 | complete |
| rolling_back | 只恢复了部分文件 | 继续相同幂等恢复，逐项记录 | paused/restored 或 needs_attention |
| needs_attention | 路径冲突、外部修改、备份缺失 | 禁止自动删除/覆盖；列出实际和预期清单 | 等待明确处理 |

输出 hash 相同不独自证明一个新路径由本操作创建；删除目标还需证明创建归属。
每个文件的发布采用 durable intent + 可识别的 stage/文件身份；恢复无法分辨外部写入时宁可留下残留。
回滚自身也必须有 planned/publishing/committed 记录并可重复执行，不能用无日志 copy 覆盖实现。
文件系统不提供可靠 flush/目录同步时只能保证进程崩溃恢复的已验证子集，不能宣称断电原子性；
具体 Linux/Windows/NAS 能力写进根目录 capabilities 和发布说明。

delete 也走文件协调器：首版若旧删除不承诺可恢复，仍先记录意图、核验身份、删除后更新引用；
若产品选择可恢复删除，则使用受限 trash/备份并明确保留期。不能在架构实施中静默改变产品语义。

## 7. 索引与缓存

初扫建立 assets 元数据；每轮按根推进 `scan_generation`，仅当该根完整遍历无结构性错误结束时，
才将该根中 `indexed_generation < current_generation` 的文件标为 `missing`。根离线、不可达或
权限失败必须保留先前资产状态并报告 `indexing`/根健康告警，不得批量标 `missing`。
事件监控只是加速信号，定时 reconciliation 修正漏报；下载/整理提交立即更新索引并递增
全局 `library_index_revision`（保存于 `settings` 或专用索引头行），扫描器按 asset revision 检测新旧，
不能用迟到扫描覆盖较新提交。mtime/size 用于免读优化；发现变化重新读标签，内容 hash 按需计算。

兼容 `compatibility-contracts.md` §2.3 新增的 `GET /api/library/files` 分页接口：
- 游标编码包含最后一行稳定排序键 `(sort_field, id)`、请求规范化后的 `filters_hash`（含 query、
  folder、sort）以及当前 `index_revision`，不暴露内部绝对路径或秘密。
- 请求未携带游标返回当前 `index_revision`、`indexing` 状态、该页 `items` 和 `next_cursor`。
- 携带游标时校验签名/结构及 `filters_hash` 与 `index_revision`：过滤或排序参数不匹配返回 400；
  若 `index_revision` 已因新入库、整理、删除或扫描变更而失效，直接返回 `409` 与 `detail`，
  由前端重置分页，不返回错页或重复/漏项列表。
- 原 `/api/files` 在兼容窗口保持原数组响应；SQL 查询均使用稳定排序 `(sort_field, id)`，
  禁止内存中取 10,000 条后再过滤模拟分页。简单前缀/精确过滤首版使用 B-tree，包含匹配与中文分词先测后选。
大封面、歌词和音频字节不进入常规列表 DTO，也不把 base64 音频塞进数据库缓存。
搜索结果只在用户引用、下载或需要兼容旧 ID 时持久化；候选缓存遵守上层协议，不能无界入库。

## 8. 旧数据迁移

### 8.1 离线输入与输出

输入为旧服务干净关闭后的一致性快照：DB、配置、engine/*.song、part/checkpoint、历史日志和音乐路径。
迁移工具默认 dry-run，在新目录生成报告；实际导入写新 Go DB，不修改旧 DB/旧缓存。
来源 fingerprint 包含旧 schema、DB 快照 hash 和导入版本；重复运行同来源要幂等或明确返回已导入。
受限 pickle 转 JSON 使用旧 RestrictedUnpickler 白名单，在离线子进程完成，拒绝未知类型和过大文件。
旧缓存损坏不等于音乐损坏：报告受影响 track ID，保留档案并标记需重新解析，禁止静默丢引用。

### 8.2 映射清单

| 旧内容 | 新位置 | 必须校验 |
| --- | --- | --- |
| config.json/.env/旧命名变量 | settings + 受限配置快照 | 原优先级、未识别源参数、秘密不入报告 |
| records 全部行 | legacy_records 原文 | 行数、key、原文 hash，作为迁移追溯而非双写 |
| track/.song | provider_tracks/media_variants/public_refs | 旧 ID 可解析、候选/试听、episodes、凭据不公开 |
| library_assets/library_paths | assets/asset_aliases/public_refs | ID 保持、每路径根归属、字段锁/来源信息 |
| favorite/collection/playlist | collections/items | 顺序、缺失引用、名称及收藏数 |
| download/downloaded | downloads/outputs/checkpoints | 输出实际存在、状态转换、过期 URL 重新解析 |
| library_job/library_operation | jobs/items/operations/files 或只读 legacy 历史适配 | 未完成操作先恢复；旧历史可恢复能力不能只归档丢失 |
| bot_link/渠道状态/AI 会话 | durable_records + 各领域适配 | 有效签名所需密钥、归属、等待检查点、重放边界 |

旧历史日志保留格式版本 `legacy-python-v1`，由 Go 历史适配器实现恢复或由受控迁移步骤转成完整新清单。
无法转换但仍在保留期内的可恢复记录必须阻止“全功能迁移完成”验收；不能只保留 JSON 就声称兼容。
旧进行中的文件操作应先由固定旧版本恢复到稳定点，再做正式快照；失败则保持 needs_attention 并阻止写切换。
旧 active 下载迁成 paused，保留续传字节；旧 active 整理迁成 paused；安全 AI 等待点可恢复核对，
正在执行的写操作不自动重放。机器人已收未确认事件按 inbox 去重策略核查。

### 8.3 切换与回滚

1. 固定旧镜像/提交；停止接单，等待当前文件事务结束，停止旧进程及其子进程。
2. 一致性备份 DB，WAL 正确 checkpoint 或使用 backup API；备份配置、音乐和历史的匹配快照。
3. dry-run、实际导入新 DB；运行外键/完整性检查、引用计数、路径存在性及抽样音频校验。
4. Go 先以只读模式启动验证，机器人和通知不发送；通过后唯一实例进入写模式。
5. 保留旧快照和版本；记录切换时刻、导入批次与 Go 首次写入时刻。
6. 写入前回滚可直接停止 Go 并恢复旧服务。Go 写入后回滚必须停止写入，恢复匹配的
   DB/配置/音乐/历史快照，或者通过已验证反向导出工具处理增量；首版不承诺反向导出。
7. 回滚会放弃切换后新增状态时，报告受影响下载/文件；不能只换旧镜像继续读已变化的音乐目录。

无完整音乐快照的部署只允许只读试运行，或接受逐操作可恢复范围内的有限回退；不可承诺任意时间完整回滚。
这项运维前置条件不要求复制用户全部音乐用于开发，开发和子代理只使用隔离 fixture。

## 9. 实施与验收合同

存储代理交付 SQL migrations、具体 store 方法、旧数据导入器、完整性报告和临时目录集成测试。
文件事务代理交付协调器、跨平台发布原语、每阶段 fault injection、恢复和清理工具。
两者共同维护状态枚举和提交方法；任何新增状态/列需通过集成负责人审阅契约，禁止各模块自建 DB。
最低方法语义：CreateDownload、CompareAndSetDownload、CommitPublishedOutputs、CreateLibraryPreview、
CommitFileOperation、RecordOperationFilePhase、LoadRecoveryWork、ImportLegacySnapshot、CheckIntegrity。
CommitPublishedOutputs/CommitFileOperation 是具体短事务入口；不是可注入任意回调的跨模块事件总线。

验收必须覆盖：

- 同一 dedupe_key 同时创建只有一个活动下载，权限不因复用绕过，音质不同不误合并。
- 下载完成到整理创建之间强制退出后，不漏建、不重复建整理任务。
- 每次持久状态前后以及每个文件动作前后 kill 服务，重启后得到表中定义结果。
- 同路径编辑、改名、跨卷、附属文件重名、外部改写、备份缺失均保护用户文件。
- committed 后旧源删除失败只留下清理告警，不把已发布正确文件当下载失败再下载。
- 旧稳定 ID、歌单引用、历史恢复、签名链接在迁移后可验证；秘密不出现在导入报告。
- 不可达挂载根不会批量删除索引；扫描与标签写入并发不会覆盖新 revision。
- SQL 事务不持有网络/文件 I/O；10 万曲库分页在固定硬件上测量而非凭 schema 断言达标。
- Go 停机后可按演练步骤恢复旧版匹配快照，恢复结果包含文件与数据库。

待实测事项：SQLite driver 的 WAL/取消行为、Windows replace 和文件锁、NAS no-replace/fsync 支持、
旧签名链接密钥与有效期兼容、多格式 helper 的写回完整性。未通过的事项应阻止相应写功能发布，
不能由执行代理自行删测试、降级语义或在报告中标成完成。
