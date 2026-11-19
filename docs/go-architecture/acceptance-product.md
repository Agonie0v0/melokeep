# Go 重构产物与真实端到端验收报告（acceptance_product）

报告日期：2026-11-15  
负责范围：`go/internal/app/`、`go/internal/httpapi/`、`go/internal/search/`、`go/cmd/`、`frontend/`  
冻结边界：未修改 `go/go.mod` 及其他内部 Go 包（`model` / `config` / `auth` / `operations` / `store` / `legacyimport` / `transport` / `helper` / `provider` / `files` / `media` / `library` / `download` / `agent` / `bot` / `navidrome`），跨包缺陷全部在本报告中记录交由 `/root` 统筹处理。

---

## 1. 历史验收边界与合规演进说明

### 1.1 历史外写缺陷归档与清理状态

- 历史原型阶段曾存在的旧版站外解析与第三方网盘转存适配器现已从主仓库彻底移除，所有内置音源收敛为 7 个开放许可与官方公开试听源，并由纯 Go `goja` 沙箱 JS 插件运行时替代任意非标准外部扩展。
- `provider.RequestContext` 维持只读解析默认边界，严禁在无授权情况下于 `Resolve` 或 `GET /api/stream/{sid}` 中发起外部写操作。

### 1.2 [诚实边界] 内置源注册数量不等于逐源全错误边界 / 真实公网联网已验

- `catalog.NewFullRegistry` 负责注册内置开放许可与官方试听源（7 个内置源 + 动态加载的 JS 插件），离线/本地环回与单测 mock HTTP 环境下完成主要校验，**在线可用性仍受外部站点状态与用户网络环境影响**。

### 1.3 跨包与未完成项清单（供 `/root` 统筹）

1. **`operations.Registry` 覆盖率（24 / 98）**：
   - `contracts/v1/operations.json` 与 `operations/catalog.go` 共定义 98 个操作，当前 `agent.RegisterAgentHandlers`（5 个）+ `app.RegisterDomainOperations`（19 个）共绑定 24 个 handler；其余 74 个操作在 `list_project_operations` / `discover` 中可见，但通过 `query_project` / `execute_project` 调度时返回 `501 Not Implemented`。
2. **曲库时长排序与前端分页目录过滤（`store` + `frontend`）**：
   - `go/internal/store/catalog.go:623`（`normalizeSortColumn`）不支持 `sort=duration`，导致 `frontend/src/MusicLibrary.tsx:100` 将 `"duration"` 回退为 `"title"` 并仅对客户端已加载页重排，大曲库跨页时长排序不准确。
   - `frontend/src/MusicLibrary.tsx:66-105` 的 `knownFolders` 仅来自已加载分页（>100 首曲目时后续目录不会提前出现在侧栏），且选择根目录 `"."` 时向 `/api/library/files` 传 `folder=""` 会返回全部目录而非仅根目录。
3. **刮削候选搜索未持久化（`httpapi` + `library`）**：
   - `go/internal/httpapi/handlers_library.go:483-500`（`handleLibrarySearchCandidates`）仅修改返回 DTO 中的 `item.Candidates` 与 `Status = "needs_review"`，未持久化回 `library.Service` / `store`（冻结包 `library.Service` 当前未暴露候选更新持久化方法）。
4. **网关占位与兼容受限端点**：
   - `go/internal/app/gateways.go:280` `botGatewayAdapter.TestConnection` 忽略前端 `{channel, config}` 负载，未向真实/测试上游发连接探测且缺少 `BotSettings.tsx:192` 期望的 `detail` 字段。
   - `go/internal/app/gateways.go:399` `botGatewayAdapter.RegisterDiscordCommands` 仅检查本地字段且未调用 Discord API、缺少 `detail` 字段。
   - `go/internal/app/gateways.go:436-448` `navidromeGatewayAdapter.DeletePreview` 返回 `{id, allowed}` 而非 `NavidromeLibrary.tsx:121` 期望的 `{path, revision}`，`DeleteSong` 固定返回 `501 ErrUnsupported`（冻结包 `navidrome.Client` 未提供 `getSong` 本地路径映射）。
   - `go/internal/httpapi/handlers_search_stream.go:1271` `handleLegacyResolve` 固定返回 `400`；`handleFiles`（`GET /api/files`）最多聚合 20 页 / 5000 条并在 `IndexRevision` 变化时直接截断返回局部数组；`handleLocalCover` 仅提取 FLAC PIC 与同目录封面文件；`handleTrackLyrics` 仅读取本地音频标签与同目录 `.lrc`。

---

## 2. 本轮已修复并验证的缺陷

1. **修复 `--read-only` 启动违反只读不变量的根因（`go/internal/app/app.go`）**：
   - 问题 1：`app.New` 原先在 `cfg.ReadOnly == true` 时仍无条件执行 `a.Files.RecoverOnStartup`、`a.Bot.RecoverOnStartup` 与 `a.Agent.RecoverOnStartup`，可能修改/清理音乐目录与数据库恢复状态。已改为仅在 `!cfg.ReadOnly` 时执行启动崩溃恢复。
   - 问题 2：`reconcileRoots` 在 `cfg.ReadOnly == true` 时仍调用 `files.RegisterRoot`（内部执行 `ProbeCapabilities`，会在用户音乐根目录创建 `.melokeep-probe-*` 临时目录、测试文件与硬链接）。已改为只读模式下绝不调用 `files.RegisterRoot`，改用 `files.RootFromRecord` 或只读内存 `files.Root` 结构；同时跳过 `data/go/staging` 目录创建。
   - 阻断测试：`go/internal/app/app_test.go` 新增 `TestReadOnlyModePreservesMusicRootsAndCrashRecoveryState` 与 `TestWriteModeBlockedByLegacyMigrationGuard`。
2. **修复真实服务启动时既有音乐目录未自动建索引（`go/internal/app/app.go`, `go/internal/httpapi/handlers_library.go`）**：
   - 问题：真实 `melokeep` 启动后 `Indexer.ScanRoot` 从未被 `app.Run` 或 `POST /api/library/watch/scan` 调用，导致音乐根目录已有音频在前端“音乐库”中始终为空。
   - 修复：在 `app.Run`（非只读模式）异步启动 `scanIndexedRoots`（退出前等待 `bgWG.Wait()`），并在 `handleLibraryWatchScan` 同步触发启用根目录的 `Indexer.ScanRoot`。
3. **修复 `search` 包并发竞态与 `RequestContext` 丢失（`go/internal/search/service.go`）**：
   - 将 `job.wg.Add(1)` 移至 `CreateSearch` 同步执行（早于 `idleTimer` 与 `go s.runJob`），`runJob` 顶部 `defer job.wg.Done()`，消除与 `cancelJob` 中 `job.wg.Wait()` 的并发竞态。
   - `searchFlight.run` 中通过 `provider.WithRequestContext(runCtx, provider.RequestContext{Config: snap})` 传入配置快照，使搜索请求生效 `SourceSettings`（Cookie / Proxy）、全局 `ProxyURL` / `ProxyScope` 与 `Revision`。
   - 新增 `TestSearchPassesRequestContextSnapshotToProvider` 与 `TestImmediateCancelDoesNotRaceWaitGroup`。
4. **修复 Web 与 AI 收藏存储分裂（`go/internal/app/gateways.go`）**：
   - `RegisterDomainOperations` 中 `GET /api/favorites` 统一读取 `durable_records("favorite")`，与 HTTP `handleFavorites` / `handleFavoriteAdd` 保持同一数据源。
   - 新增 `TestFavoritesSplitBrainFixedBetweenHTTPAndOperations`。
5. **修复批量下载 `Idempotency-Key` 冲突（`go/internal/httpapi/handlers_downloads_files.go`）**：
   - `handleDownloadEnqueue` 在 `len(items) > 1` 时为每个子项拼接 `":"+strconv.Itoa(idx)`，与 `gateways.go` 语义一致，避免第 2 首起触发 `409 Conflict`。
   - 新增 `TestBatchDownloadEnqueueWithIdempotencyKey`。

---

## 3. 最终验收实测结果（全部在最终代码提交后执行）

### 3.1 Go 单元测试、集成测试与静态检查（Windows `CGO_ENABLED=0`）

- `go test -count=1 ./internal/app/... ./internal/httpapi/... ./internal/search/... ./cmd/melokeep/... ./tests/fault/... ./tests/integration/...`：**全部通过**
  - `internal/app`: PASS (`8.912s`)
  - `internal/httpapi`: PASS (`8.830s`)
  - `internal/search`: PASS (`0.507s`)
  - `cmd/melokeep`: PASS (`6.405s`)
  - `tests/fault`: PASS (`8.393s`)
  - `tests/integration`: PASS (`14.999s`)
- `go test -count=10 ./internal/search/...`：**全部通过** (`4.524s`)
- `go vet ./internal/app/... ./internal/httpapi/... ./internal/search/... ./cmd/melokeep/...`：**通过**（无告警）
- 注：Linux `-race` 复测与普通 build 下的 `TestLibraryIndex100kPaginationLatency` 实测由 `/root` 在 Linux 环境统筹执行。

### 3.2 前端构建、单测与真实 Go 服务浏览器端到端验收

- `cd frontend && npm test`：**8/8 通过**
- `cd frontend && npm run format:check`：**通过**
- `cd frontend && npm run build`：**通过**（`tsc -b && vite build` 生成 `frontend/dist`）
- `cd frontend && node tests/browser.mjs`（Edge headless + Playwright mock）：**通过**
- `cd frontend && node tests/ai-browser.mjs`（Edge headless + Playwright mock）：**通过**
- `cd frontend && node tests/real-server-e2e.mjs`（**零 `/api/*` mock**：编译真实 `melokeep` 二进制加载真实 `frontend/dist`、隔离临时 `dataDir` + `musicRoot` 含真实 WAV 文件、启用 `--auth-user`/`--auth-pass`、Edge headless）：**通过**
  - 验证项：未登录 `GET /api/config` 返回 `401` -> 真实表单登录下发 `HttpOnly` `melokeep_session` Cookie -> `GET /api/health` 返回 `ok: true, engine: "melokeep-native"` -> 音乐库自动索引并展示 `Alpha.wav` 与 `Beta.wav` -> `Range: bytes=0-15` 播放请求返回 `206 Partial Content` / `audio/wav` / `RIFF` -> `POST /api/playlists` 与 `GET /api/playlists` -> `GET /api/agent/settings` 返回 `200` -> 零 `pageerror` 与零 `5xx` -> 切换 `--read-only` 重启后写请求（`POST /api/downloads`、`PUT /api/config`）均返回 `403`，`musicRoot` 目录与音频字节零变更且无 `.melokeep-probe-*` 残留。
