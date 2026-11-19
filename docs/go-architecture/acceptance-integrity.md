# Go 重构数据与文件安全验收测试报告

本报告记录针对 MeloKeep Go 重构核心数据层、文件协调器、操作幂等注册表及旧版迁移模块的验收测试、发现的真实缺陷、修复内容与验证结果。所有测试均使用隔离临时目录与构造固件，未读取或修改真实 `data/`、`config.json` 或 `.env`。

## 1. 验收范围与测试环境

- **验收范围**：`go/internal/operations`、`go/internal/store`、`go/internal/files`、`go/internal/legacyimport`、`go/tests/fault` 及新增集成与模糊测试套件 `go/tests/acceptance_integrity`。
- **执行环境**：Windows (`windows/amd64`)、`pwsh.exe` 7、`CGO_ENABLED=0`（`C:\Users\Administrator\AppData\Local\Programs\GoSDK\go\bin\go.exe`）；跨平台静态检查覆盖 `windows/amd64`、`linux/amd64`、`linux/arm64`。
- **约束遵守**：未修改 `go/go.mod`、`go/migrations/0001_initial.sql` 及上述范围外的业务包。

## 2. 发现并修复的真实缺陷

### 2.1 并发幂等性与持久化冲突语义（`go/internal/operations/registry.go`、`go/internal/store/idempotency.go`）

- **缺陷**：
  - `Registry.Dispatch` 并发收到相同 `(actorKey, opID, idempotencyKey)` 时，多个协程可能同时通过先验查询并重复执行 handler，且忽略 `r.idempotency.Save(...)` 返回的错误。
  - `IdempotencyStore.Save` 与 `MemoryIdempotencyStore.Save` 使用覆写语义，后到请求可能覆盖先到成功记录，且不同 `PayloadHash` 重用同一幂等键时未稳定返回 `409 Conflict`。
- **修复**：
  - 在 `Registry.Dispatch` 中引入按 `(actorKey, opID, idempotencyKey)` 键控的 `acquireFlight` 通道串行化机制：同 payload 并发请求仅执行一次 handler，其余等待首个执行结果；冲突 payload 返回 `409 Conflict`。
  - 检查并传播 `r.idempotency.Save` 错误；将 `IdempotencyStore.Save` 与 `MemoryIdempotencyStore.Save` 改为保留首次未过期记录（first-writer immutability），当已存在未过期记录且 `PayloadHash` 不一致时返回 `ErrIdempotencyConflict`（HTTP `409`）。

### 2.2 文件协调器恢复与回滚安全性（`go/internal/files/coordinator.go`、`go/internal/files/recovery.go`）

- **缺陷**：
  - `recovery.go` 向 `file_operations.state` 写入了 `"paused"` 与 `"failed"`，违反 `0001_initial.sql` 的 `CHECK` 约束并导致静默失败。
  - `executeDownloadPublish` 未在附属文件 `OperationFileSpec` 上记录 `Stage` 的 `StatIdentity`，崩溃恢复无法确认 hardlink 目标归属。
  - `Coordinator.Restore` 未限制仅恢复 `"edit"` / `"move"`（允许对 `download_publish` 执行），未校验目标文件 `AfterHash` 与 `FileIdentityPair.Target` 归属即尝试清理，忽略 `os.Remove` 错误（Windows 句柄占用时仍标记为 `cleaned`），也未在 `operation_files` 记录完整阶段迁移或通过 `CommitFileOperation` 提交。
- **修复**：
  - `recovery.go` 将中断/失败的持久化状态统一映射为合法 `CHECK` 值 `"rolled_back"`，检查 `SetFileOperationState` 错误并在失败时回退为 `"needs_attention"`，同时保持 `RecoveryReport.PausedOps` 计数与现有故障测试兼容。
  - `executeDownloadPublish` 为附属文件补齐 `Stage` 文件标识。
  - `Coordinator.Restore` 限定仅允许 `"edit"` / `"move"`（`download_publish` 返回 `409`）；清理前验证 `AfterHash` 与目标 `StatIdentity`；检查 `os.Remove` 错误避免 Windows 锁定文件被误标为已清理；为 `restore` 初始化 `"planned"` 并逐文件推进阶段；使 `rollbackOperation` 能识别 `Kind == "restore"` 的同路径恢复哈希，并统一使用 `CommitFileOperation` 提交。

### 2.3 旧版数据迁移跨平台、密钥脱敏与索引规范化（`go/internal/legacyimport/importer.go`）

- **缺陷**：
  - `detectPythonBin()` 与 `findVenvPython()` 在非 Windows 环境仍优先检查 `.venv/Scripts/python.exe`，造成跨平台测试/运行失效。
  - `.song` JSON 解码与 Python `safe()` 仅在顶层剥离敏感字段，嵌套 `episodes` 中仍可能残留 `download_url`、`default_download_headers`、`default_download_cookies`、`downloaded_contents`、`downloaded_contents_b64`。
  - `analyzeLegacyPlan` 中部分映射到 `needs_attention` 的活跃操作未设置 `BlocksWriteSwitch = true`。
  - 根目录 `library_assets` 的 `folder` 字段可能保留 `"."` 而不是空串 `""`，且在 `'insensitive'` 旧根下未统一小写 `path_key`；收藏与歌单导入可能丢失 `asset:<id>` 引用。
- **修复**：
  - 按 `runtime.GOOS == "windows"` 区分 `.venv/Scripts/python.exe` 与 `.venv/bin/python` / `python3` / `python`。
  - 新增 `DecodeJSONSong` 与递归 `stripSongSecrets`（同步更新 Python `safe()` 递归过滤）。
  - 为所有映射到 `needs_attention` 的遗留操作强制设置 `BlocksWriteSwitch = true`。
  - 规范化根目录 `folder` 为 `""`、在 `'insensitive'` 模式下规范化小写 `path_key`，并保留 `collection` / `favorite` 中的 `asset:<id>` 项引用。

## 3. 新增验收测试与模糊测试覆盖

在 `go/tests/acceptance_integrity/` 新增以下测试与 fuzz 目标：

- `idempotency_concurrency_test.go`：
  - `TestAcceptanceConcurrentIdempotencySamePayloadExecutesOnce`
  - `TestAcceptanceConcurrentIdempotencyPayloadConflictReturns409AndPreservesWinner`
  - `TestAcceptanceForceNewIntentNonceAndPermissions`
- `files_recovery_restore_test.go`：
  - `TestAcceptanceCrashRecoveryTransitionsDBStateAndIsIdempotentAcrossRestarts`
  - `TestAcceptanceRestoreNewSidecarIdentityAuditAndCrashSafety`
  - `TestAcceptanceRestoreSamePathCrashAndWindowsLockedTarget`
  - `TestAcceptanceDownloadPublishSidecarCrashOwnershipAndPathEscapes`
- `store_safety_test.go`：
  - `TestAcceptanceStoreDownloadDedupeOwnershipAndAtomicity`
  - `TestAcceptanceStoreScanGenerationPaginationAndOutbox`
- `legacy_migration_test.go`：
  - `TestAcceptanceLegacyMigrationAssetsCollectionsSecretsAndIntegrity`
- `fuzz_test.go`：
  - `FuzzValidatePath`
  - `FuzzDecodePaginationCursorAndPayloadHash`
  - `FuzzDecodeJSONSong`

## 4. 验证执行结果

1. **单元与集成验收测试**（`CGO_ENABLED=0`）：

```powershell
go test -count=1 ./tests/acceptance_integrity/... ./internal/files/... ./internal/store/... ./internal/legacyimport/... ./internal/operations/... ./tests/fault/...
```

全部 6 个包通过：`tests/acceptance_integrity` (2.170s)、`internal/files` (1.327s)、`internal/store` (0.544s)、`internal/legacyimport` (0.990s)、`internal/operations` (0.189s)、`tests/fault` (1.620s)。

2. **模糊测试**（`CGO_ENABLED=0`，各 3 秒）：

- `FuzzValidatePath`：3,458 execs，11 new interesting，PASS
- `FuzzDecodePaginationCursorAndPayloadHash`：172,380 execs，45 new interesting，PASS
- `FuzzDecodeJSONSong`：232,021 execs，68 new interesting，PASS

3. **跨平台静态检查与工作区格式检查**：

- `go vet`（`windows/amd64`、`linux/amd64`、`linux/arm64`）：均 PASS。
- `git diff --check`：无空白符或格式错误。

## 5. 未覆盖项与后续交接说明

- **Linux `-race` 竞态检测**：本次验收在 Windows (`CGO_ENABLED=0`) 环境中执行，未运行 `-race`；需由协调会话在 Linux 容器环境中执行 `go test -race -count=1 ./tests/acceptance_integrity/... ./internal/files/... ./internal/store/... ./internal/legacyimport/... ./internal/operations/...`。
- **硬件与长稳基准**：真实 NAS/SMB 挂载点掉线测试、100k 曲库分页压测与 24 小时耐久内存/句柄泄漏观测超出本模块单元测试范围，需在完整集成验收环境中按 `docs/go-architecture/acceptance.md` 推进。
