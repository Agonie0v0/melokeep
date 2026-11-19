# 兼容契约、Provider 与外部工具边界

状态：设计规范，供实现任务包引用；不是已验证的新服务接口实现。

源码基线：`c17cf18d4edb75fb3c0aa9f4be7d24e6aca87f09`。本次只做静态抽取和源码核对，没有启动旧服务或在线测试音源。

## 1. 兼容的唯一判定依据

第一版 Go 的目标是保留基线已有的可观察行为与数据，不以旧实现的内部类/线程/文件布局作为新实现接口。前端现有类型与测试只是部分证据，运行态 API/schema、真实响应样本和失败用例应共同构成冻结契约。

随本文交付的清单：

- [api-inventory.csv](api-inventory.csv)：131 处静态路由声明，展开 AI 前缀后有 126 个唯一 method/path；5 处 legacy 定义被前置 engine_api 路由遮蔽。
- [Provider 插件规范](../provider-plugin-spec.md)：`Provider Plugin API v1` 沙箱契约与 `ctx` 工具箱说明。

126 不是已运行确认的有效公开端点数。清单不包含框架自动路由、挂载静态资源和自动 HEAD/OPTIONS；路由顺序、动态注册与匹配关系仍需启动后核对。不得直接把所有装饰器生成成 Go 路由。

静态确认的遮蔽项是 legacy 的 GET health、GET/PUT config、GET stream/{sid}、GET files，均由 engine_api 对应路由优先处理。其余 legacy 路由继续存在，例如 resolve、episodes、logs、维护等。

### 1.1 冻结任务的具体输出

在临时配置、临时数据库、临时音乐目录启动固定旧版，禁止读取部署凭据；关闭真实机器人、AI和目录监控外部副作用。使用项目锁定的依赖版本并记录环境。

输出到 `contracts/v1/`（实施阶段创建）：

```text
manifest.json                 基线提交、依赖、生成时间、采集场景
routes.json                   按实际优先级展开的有效路由
openapi.json                  实际 FastAPI schema
operations.json               AI 可发现的操作及读写标记
http/<domain>/*.json           request / status / selected headers / body
sse/search/*.json              初次订阅、重连、取消、失效的事件样本
config/*.json                 默认配置、隐藏密钥、历史名称迁移样本
providers/<source>/*.json      脱敏请求响应、候选、解析、歌单样本
```

只归一化明确非确定字段，例如时间、测试 UUID、临时绝对目录；保留缺失字段和 null 的区别、数组顺序、错误状态、音质标记、状态转换和权限拒绝。签名协议测试的时间/随机数必须注入固定值，不能直接删除签名字段后对照。

route 每行增补 handler、请求/schema 摘要、鉴权类型、副作用类型、响应类型、兼容测试 ID、迁移 owner、完成状态。当前 CSV 的 `requires_runtime_schema_and_fixture` 不得未经采集改成通过。

## 2. HTTP 与前端兼容

### 2.1 稳定约束

| 边界 | v1 规则 |
| --- | --- |
| API 根路径 | 保留 `/api`；应用默认端口 8765 |
| Web 客户端 | 保留同源 fetch 与 `credentials: same-origin` |
| JSON 请求 | 保留 Content-Type 与 `X-MeloKeep-Request: 1`；浏览器写方法缺该头仍拒绝 |
| 登录 | 保留 `melokeep_session` Cookie、HttpOnly、SameSite=Strict、`/api` 路径、可配置 Secure；切换版本允许重新登录 |
| 登录限流 | 保留失败次数窗口及 Retry-After 语义；代理后的真实来源策略必须明确配置，不能信任任意转发头 |
| 错误 | 兼容 `detail` 顶层字段和既有状态码；不要第一版统一改成新 envelope |
| 资源响应 | JSON、音频字节、图片、CSV、HTML、SSE 分开编码；保持各路由真实 Content-Type |
| 文件/媒体 | 保留 Range/If-Range、206/416、Content-Range、Content-Length、类型与断线取消 |
| 配置写入 | 保留原 PUT 的替换/合并语义；隐藏凭据占位不能覆盖真实秘密 |
| 旧路径 | 已迁移资产的旧路径引用与稳定 ID 可解析，内部绝对目录不作为新的公开身份 |

所有不兼容变化须记录“旧行为、原因、新行为、客户端修改、迁移措施”。安全缺陷可以修复，但应显式审批变更说明，不能以兼容为由复现缺陷或静默改变正常行为。

### 2.2 DTO 与领域模型分离

`frontend/src/types.ts` 是 v1 输出约束之一。Go 内部可使用更清晰模型，通过 adapter 映射到 Track/Quality/DownloadJob/LibraryFile/LibraryJob/Config 等旧 JSON。

必须保留：

- Track.id 是公开身份；source 是公开源别名，source_id 是上游曲目标识，不是同一概念。
- Track.duration 以秒表示；size_bytes 可 null；qualities 是数组，不把未知音质自动补为无损。
- playback 的 state/url/codec/bitrate/detail/preview_only 保持形状；试听不能标为完整音频。
- 本地资产、Navidrome 项与远端候选可以共享旧 Track DTO，但内部身份类型必须区分。
- 下载后的文件存在与后续 processing 成功是两件事；保留独立 processing 信息。
- 数值超出 JavaScript 安全整数范围时拒绝/限制或使用明确的新版本字段，不能悄悄字符串化现有字段。

旧数据库迁入的 ID 原样保存。旧 candidate ID 包含配置指纹，已解析 ID 又包含音质/大小等因素，不能把所有旧 ID 重新哈希成一个新值。新领域身份与旧 public ID 之间建立映射；同一搜索候选解析后继续使用它已发布的 public ID。

### 2.3 大曲库分页的兼容办法

新增 `GET /api/library/files?limit=100&cursor=...&query=...&folder=...`，返回 `{items,next_cursor,index_revision,indexing}`。这是新契约，不修改原 `/api/files` 的数组形状。

- limit 默认 100、最大 500；游标绑定排序、过滤条件和索引版本。
- 文件名/ID 排序必须稳定，游标不可包含秘密；过滤变更必须丢弃旧游标。
- 索引版本变化时返回明确 `409` 与 `detail`，前端重置列表；不返回悄悄错页的数据。
- 首扫未完成时返回当前已索引项和 indexing=true；前端明确显示扫描状态。
- 前端、AI 操作目录和机器人曲库读取在同一个任务包中切换到新接口；旧 `/api/files` 在兼容窗口保留，需要访问旧接口的大列表耗时列为已知限制。

若后续选择弱一致游标以避免频繁重置，必须修改上述契约并新增并发索引分页测试，不能由单个执行代理自由选择。

## 3. 搜索 SSE 契约

### 3.1 已观察到的基线行为

- `POST /api/search/jobs` 接收 keyword、sources、refresh，返回 `{id}`。
- 空关键词/无有效源返回 400；活动搜索达到 6 个返回 429。
- 当前后端发送 `track`、`source`、`done` 事件；前端同时监听 `candidate`，不代表当前服务必发 candidate。
- `track` data 为 Track；同一 id 的后续事件更新现有条目，而非追加重复项。
- `source` data 为 `{id,status:{state,count,detail?}}`；部分结果后失败仍保留已有曲目。
- `done` data 为 `{cancelled:boolean}`，每 job 只产生一个终态事件。
- 序号从 1 单调递增；格式包含 `id:`、`event:`、`data:` 及空行。
- 重连游标取 query after 与 Last-Event-ID 的最大值，发送严格大于游标的事件。
- 10 秒空闲时发送 `: keepalive` 注释；Cache-Control=no-cache，X-Accel-Buffering=no。
- 新 job 30 秒无人订阅会取消；最后订阅者断开后给予 10 秒重连宽限。
- job 已被淘汰返回 404；DELETE 即使不存在也返回 `{cancelled:true}`。

### 3.2 Go 的资源约束

仍使用 SSE，不引入 WebSocket。每个 job 的事件记录必须可重放；先发送到有界 job 日志，再通知订阅者，不因某个慢订阅者阻塞 Provider。

建议默认每 job 事件日志上限 8 MiB、整个搜索组件 32 MiB，单事件 256 KiB；这些是新资源限制，需以所有合法基线 fixture 校准。达到上限时终止该搜索并产生可读的容量错误及 done，预留终态事件空间；不得任意丢中间 track 却声称完整成功。慢客户端只关闭自身连接，保留重连窗口。

搜索日志与缓存独立计费；缓存命中仍按同一 DTO/事件规则输出。请求合并 key 包含源、归一化查询、结果数、账号/代理/影响结果的配置版本；不把秘密原文写入 key/log。一个订阅者取消不能杀掉其他仍使用的共享搜索；最后用户离开才取消上游。

## 4. 业务操作与权限契约

HTTP、机器人和 AI 使用同一业务服务入口。Operation 目录只做描述、参数校验入口和权限绑定，不成为另一个工作流引擎。

每个 Operation 至少定义：

```text
id                  稳定操作名；保留旧 METHOD /path 作为兼容别名
input_schema        结构化输入规则，HTTP 与 AI 共享
output_schema       可公开输出，不含凭据或内部指针
effect              read / local_write / external_write / maintenance
required_permission 最小能力要求
handler             对既有业务服务的调用
idempotency         required / optional / not_applicable
```

请求上下文携带 actor(channel,user)、已验证的权限、request_id、deadline、cancellation。权限由服务端鉴权/渠道白名单生成，模型和浏览器不得自行声明管理员权限。

有副作用的请求支持应用层幂等键，作用域为 actor + operation + key。第一次执行记录规范化输入摘要与结果引用；同键同输入返回同一任务/结果，同键不同输入返回409。外部回调的平台消息ID可作为其幂等键来源。传输重试去重与业务上“允许用户再次下载同一曲目”是不同策略：新幂等键不能被永久的曲目唯一约束误拦，现有下载复用策略由下载服务另行决定。

具体到下载请求：需要绕过已有文件/活动任务复用并创建新下载时，由下载服务语义通过 `force_new`（或显式每次请求的 `intent_nonce`）及 `active dedupe` 规则处理；不要用同一个应用层幂等键去承载多次重复下载语义。

兼容规则：

- 保留 discovery 的操作别名、参数/响应语义；不通过拼接 URL 或放行内部 HTTP 头来执行 AI 操作。
- query 只读；execute 必须具备本轮写权限，且最终业务入口仍检查资源归属/版本/路径。
- 排除登录接口、媒体字节、事件流、对话递归调用入口；agent/settings 是否开放以旧目录和显式权限为准。
- 模型输出不能指定任意外部 URL、SQL、shell、文件绝对路径或凭据。
- `[已隐藏]` 输入只代表保留现有秘密；返回模型的结果按字段和 URL 脱敏，不在保存执行日志之后才脱敏。
- 停止 AI 取消当前推理/安全等待，不自动取消它已创建的独立下载；重启不重放不确定的写操作。
- 机器人账号与网页共享曲库，但任务控制权限、会话和通知交付按 actor 隔离。

读取与解析操作不应额外产生未授权的业务外部写入。

## 5. Provider 接口与能力边界

### 5.1 最小接口语义

以下是语义契约，具体 Go 类型由核心契约任务统一提交，Provider 代理不得各自定义一套 Track。

```text
Search(ctx, query, options, emitCandidate) -> source summary/error
Resolve(ctx, candidateRef, qualityPolicy) -> resolved media list/error
Playlist(ctx, locator, emitEntry) -> playlist summary/error   [可选能力]
Capabilities() -> declared capability snapshot
```

Provider 不直接写主数据库，不启动下载队列，不发布正式音乐文件，不发送机器人通知。只做平台协议及元数据/媒体地址解析；所有网络经统一 transport。配置是创建请求时的不可变快照，不能跨并发请求修改共享 provider 字段。

音质身份与短期可播放媒体解析结果拆分为两个模型：

- `MediaVariant`（持久音质身份）：仅包含 `variant_id`、upstream identity、quality label、codec、bitrate、lossless evidence、preview flag、protocol 及已知的 size/duration evidence；**严禁持久化或携带临时下载/播放 URL、`expiry` 或请求凭据/Header**。
- `ResolvedMedia`（短期运行时结构）：绑定对应的 `MediaVariant`（或 `variant_id`），额外携带临时 URL、`expiry` 及所需请求凭据引用，用于当次播放代理或下载执行。`ResolvedMedia` 仅驻留内存或短期解析缓存，**禁止写入持久化的 `variants` 存储**。

URL 和真实凭据只在服务端使用；提供给前端的是受控播放入口。Resolve 没有成功验证完整音频时，不因扩展名为 flac 就断言完整无损。

错误分类统一为 cancelled、deadline、rate_limited、auth_required、unavailable、unsupported、invalid_response、transport、internal，并保留可脱敏的 provider code 与 retry-after；“没有匹配曲目”和“平台请求失败”分开。

重试只在能证明安全的读取阶段进行，次数受总 deadline 约束；账号刷新、网盘转存等副作用不得随通用 HTTP retry 重复执行。

### 5.2 内置源与 JS 插件扩展

| 来源类型 | 数量 / 机制 | 范围 |
| --- | --- | --- |
| `builtin` 开放与官方试听源 | 7 | `AudiusMusicClient`、`FMAMusicClient`、`CCMixterMusicClient`、`OpenGameArtMusicClient`、`WikimediaCommonsMusicClient`、`JamendoMusicClient`、`ITunesMusicClient`（其中 iTunes 显式标记 `PreviewOnly: true`，Jamendo 需用户自行配置 `client_id`） |
| `plugin` JS 沙箱扩展 | 动态注册 | 基于 `github.com/dop251/goja` 实现 `MeloKeep Provider Plugin API v1`（见 [provider-plugin-spec.md](../provider-plugin-spec.md)），支持搜索、解析、歌单、分集、歌词与元数据钩子 |

每个内置源与插件能力需具备清晰的能力声明（`search`、`playlist`、`episodes`、`lyrics`、`metadata`）与离线单元测试 fixture；在线测试未配置账号或网络受限标为 blocked/not_run，不得将未实现的歌单或试听片段伪装成完整无损音频。

## 6. Python/helper 的确定边界

最终仅保留一个按需轻量兼容组件：Mutagen 标签处理器（`helpers/mutagen/tagger.py`）。它不是第二套 Web 后端，主服务不依赖常驻 Python。

### 6.1 进程协议 v1

采用 stdin/stdout UTF-8 NDJSON。每个进程一次只执行一个任务，终态后退出；首版不做驻留池，也不做多路复用。初始不超过 1 个 tagger 并发，并与全局资源预算联动。启动参数只包含工具类型和非敏感任务号；参数通过匿名管道传递，不能进入命令行或日志。

```json
{"v":1,"type":"hello","helper":"tagger","implementation":"mutagen-wrapper","capabilities":["read_tags","write_tags","batch_read_tags"]}
{"v":1,"type":"run","id":"task-uuid","operation":"write_tags","timeout_ms":30000,"input":{"stage_path":"...","tags":{}}}
{"v":1,"type":"progress","id":"task-uuid","seq":1,"data":{"stage":"tagging"}}
{"v":1,"type":"result","id":"task-uuid","seq":2,"data":{"ok":true}}
```

第二行是 Go 发给 stdin 的消息，其余由 helper 输出。错误终态替代 result：

```json
{"v":1,"type":"error","id":"task-uuid","seq":2,"error":{"code":"deadline","message":"标签写入超时","retryable":true}}
```

约束：

- 3 秒内 hello；版本不匹配/未知 helper 类型直接失败，不猜测兼容。
- run.id 在所有输出一致，seq 单调递增；只允许一个 result/error，正常完成要求终态和退出码0同时成立。
- timeout_ms 是相对剩余预算；Go 持有权威总 deadline。默认标签处理 120 秒，可在操作级根据已验证场景配置，不能由模型任意延长。
- stdout 仅协议；诊断到 stderr，脱敏后保存有界尾部。单行上限 1 MiB，总输出 16 MiB，stderr 尾部 256 KiB，超限关闭任务并终止进程树。
- 使用带上限的读帧器，不依赖 Go Scanner 的默认 64 KiB 上限，也不无界读取整个 stdout。
- 大图片/音频不走 base64 NDJSON；Go 在私有任务目录准备文件，协议只传受限路径/相对名、长度、hash、mime。
- Go 收到终态但进程未在 1 秒内退出，视为异常清理；不能发布未重新校验的输出。

Tagger 的扩展只读批量操作 `batch_read_tags`：

- 仅作为扫描阶段且 Go 本地直接标签读取失败/不支持容器时的降级补充，允许一次请求批量读取最多约 200 个文件路径的只读元数据；**写标签操作仍严格维持单文件任务隔离的 helper 调用**，不做批量写。
- `batch_read_tags` 受同样单任务短生命周期、独立总超时与 stdout/stderr 有界输出上限约束；返回数组中为每个文件记录单条成功元数据或独立失败原因，单条失败不影响其余文件读取，且整个批次失败时曲库扫描仍可降级保留基础文件信息而不阻塞索引。

### 6.2 取消与异常

Go 先发 `{v:1,type:"cancel",id}` 并关闭输入，最多等待 1 秒；超时或 helper 不支持协作式取消，终止整个进程树。Unix 使用独立进程组，Windows 使用带 kill-on-close 的 Job Object，Windows 所有后台进程无窗口启动。不能只 kill 父 Python 留下子进程。

父服务异常退出时也必须清理 helper：Windows Job Object；Linux 以进程监督/parent-death 配置与容器生命周期实现，并测试子孙进程。其他平台若不能保证父死清理，应在任务启动与下次启动时核对遗留 PID 的出生标识，不能仅凭复用的 PID 终止进程。

异常退出、无终态、JSON 错误、超限、结果路径非法均为任务失败；只清理归属本任务且已验证根目录的临时文件。不得删除源音频或整个共享暂存目录。

### 6.3 文件与边界权限

Tagger 只读 Go 提供的原件副本，写任务目录中的派生文件，不改用户正式曲库、主数据库或操作日志；Go 校验输出类型/长度/hash/音频和元数据结果后交给文件事务发布。helper 的健康运行不是文件发布成功。

环境变量按白名单构建，工作目录专用、权限受限。进程隔离主要提供终止和故障边界，不等于完整 OS 安全沙箱；第三方音源插件统一在纯 Go `goja` 沙箱中执行，禁止加载用户 Python 插件。

## 7. 契约交接与变更规则

1. 核心代理先提交 DTO、错误模型、Provider、Operation、helper 帧定义及公共测试样本，生成契约版本号。
2. 功能代理只消费冻结契约；发现无法表达的既有行为，提交带真实例子的变更请求，不直接改公共类型。
3. 变更请求说明兼容影响、依赖任务、测试和迁移；协调代理批准后单独集成公共契约，再通知相关代理更新。
4. 第一版公开 API 保持现有路径/字段，新增分页与插件管理等能力采用新增路由；删除旧路由必须在后续版本明确弃用。
5. 合并门槛包括：所有已冻结契约 fixture 通过、内置开放源与插件沙箱契约无遗漏/重复、API 清单每项有迁移决定、Python helper 不写主库/正式文件、旧 UI 主路径通过。

本文未承诺所有在线音源可用，也未声称兼容契约已采集完毕。第一阶段代理的交付物就是把这里明确的采集清单变成可执行的契约测试，并在开始并行实现前冻结共享边界。
