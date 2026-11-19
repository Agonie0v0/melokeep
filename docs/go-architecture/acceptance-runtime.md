# MeloKeep Go Refactoring — Runtime, Performance, Packaging & Helper/Transport Acceptance Report

**Agent**: `/root/acceptance_runtime`  
**Date**: 2026-11-15 (Asia/Shanghai)  
**Base Commit**: `c17cf18` (with local uncommitted refactor + acceptance fixes)  
**Scope**: Runtime performance, bounded concurrency, Docker packaging, helper supervisor/scripts, and transport security/resilience acceptance. No real `data/`, `config.json`, or `.env` was read or modified; all runs used isolated temp directories or anonymous container volumes.

---

## 1. Executive Verdict & Blocking / Failed Items (Nothing Hidden)

| Area | Status | Notes |
|---|---|---|
| Windows Go unit & acceptance tests (`helper`, `transport`, `media`, `acceptance_runtime`) | **PASS** | `CGO_ENABLED=0 go test -v -count=1` passed all 4 packages after fixes (`helper_test.go`, `media_test.go`, `supervisor.go`). |
| Cross-OS `go vet` & `go build` (`windows/amd64`, `linux/amd64`, `linux/arm64`) | **PASS** | `CGO_ENABLED=0` vet passed all 3 targets; Linux static builds produced 16.52 MB (`amd64`) and 15.34 MB (`arm64`). |
| Docker image build & runtime smoke (`melokeep-acceptance:test`) | **PASS** (after `Dockerfile` fix; mirror tag workaround) | Fixed missing `requests[socks]` and missing `MELOKEEP_PYTHON` env var. Container starts as UID/GID `10001:10001`, serves `/api/health`, UI `/`, and detects `ffmpeg`/`ffprobe`/`fpcalc`/`mutagen` all `true`. Direct `docker.io` OAuth failed on host network; pulled `node:24-bookworm-slim` & `python:3.14-slim-bookworm` via `docker.m.daocloud.io/library/*` and tagged locally. |
| Linux `-race`: `go/internal/helper` | **PASS** (Code bug **FIXED**) | Found and fixed real `os/exec` `StdoutPipe()` + `cmd.Wait()` race in `go/internal/helper/supervisor.go` (`TestSupervisorLargeLineAndLimitsAndRedaction` and `TestRealMutagenTagger7FormatsAndBatch200` **PASS** under `CGO_ENABLED=1 -race`). Legacy external-extractor helper has since been removed in favor of builtin open providers and Goja JS plugins. |
| Linux `-race`: `go/internal/download` | **FAIL (Out-of-scope test bug)** | Reproduced in `melokeep-acceptance-linux:local` with `CGO_ENABLED=1 go test -race -v -timeout 45s -count=1 ./internal/download/...`: `TestDownloadRetryPauseCancelDedupeForceNewAndAutoOrganize` failed (`音频处理失败 ... Invalid data found when processing input`) and `TestDownloadCancelDuringPublishingWaitsAndPreservesCommittedAsset` timed out waiting on `<-enteredPub`. **Root cause**: `go/internal/download/download_test.go` hardcodes `E:\tool\ffmpeg\bin\ffmpeg.exe` in `sampleMP3Bytes` and `setupDownloadEnv`. On Windows (without `E:\tool\...`), `ffmpegPath` became `""`, skipping verification; on Linux, `media.NewRunner` / `LookPath` resolves `/usr/bin/ffmpeg` while `sampleMP3Bytes` still falls back to fake `"ID3...MP3FRAME"` bytes, failing FFmpeg PCM verification before `Publish`. Not fixed here (`go/internal/download/` is outside `/root/acceptance_runtime` ownership). |
| `NOTICE.md` third-party attribution | **RESOLVED** | Historical gap noted at the time; `NOTICE.md` has since been rewritten to list actual Go (`modernc.org/sqlite`, `github.com/dop251/goja`, etc.), Python helper (`mutagen`, `requests[socks]`, `python-dotenv`), media tools, and frontend/mobile dependencies under `LICENSE` (MIT). |
| 24h endurance, real online network checks | **NOT EXECUTED** | Only 60s short steady-state soak (`mode: short_steady_state`) and offline/mocked HTTP provider suites were run. |

---

## 2. Fixes Applied by `/root/acceptance_runtime`

1. **`go/internal/helper/supervisor.go` (Linux `-race` pipe close race fix)**:
   - Replaced `cmd.StdoutPipe()` / `cmd.StdinPipe()` with explicit `os.Pipe()` pairs (`stdinR/stdin`, `stdoutR/stdoutW`) and closed child ends in the parent immediately after `StartInTree(cmd)`.
   - Previously, `waitDone <- cmd.Wait()` ran concurrently with the stdout reader goroutine; per Go's `os/exec` contract, `cmd.Wait()` closes `StdoutPipe()` as soon as the child exits, even if the reader goroutine has not yet read `io.EOF`, causing fast exits under Linux `-race` to hit `read |0: file already closed` (`helper 终态后存在非法输出`).
2. **`Dockerfile` (container runtime dependency & env fix)**:
   - Ensured `"requests[socks]>=2.32,<3"` (installs `requests` + `PySocks`) and `MELOKEEP_PYTHON=/usr/local/bin/python3` are present for the helper runtime.
3. **`go/internal/helper/clients.go` & `helpers/mutagen/tagger.py` (symlink escape hardening)**:
   - Hardened `ensureInsideRoot` with `os.Lstat` symlink rejection and `filepath.EvalSymlinks` canonicalization before `filepath.Rel`.
   - Added `is_symlink()` defense-in-depth checks for `stage_path` and `cover.path` in `helpers/mutagen/tagger.py`.
4. **Legacy helper retirement note**:
   - External Python extractor helper scripts have since been retired in favor of Go native builtin open providers and Goja JS plugins.
5. **`go/internal/helper/helper_test.go` & `go/internal/media/media_test.go` (cross-platform test fixes)**:
   - Updated `findTestPython` to pick `.venv/Scripts/python.exe` on Windows vs `.venv/bin/python{,3}` or `PATH` on Unix and verify `import sys` execution.
   - Changed `TestParseCUE` from hardcoded `C:\music\album.flac` to `filepath.Join(t.TempDir(), "album.flac")`.
6. **`tools/benchmark_go.py` (continuous RSS sampling & realistic 100k + soak harness)**:
   - Added `RssSampler` background thread (`--sample-interval-ms`, default `50` ms), `2` ms health polling, median idle RSS after `0.5s` settle, `folder`/`name` seeding + filter/sort latency in 100k tests, and `--soak-seconds` (`mode: short_steady_state`).
7. **`go/tests/acceptance_runtime/acceptance_runtime_test.go` (independent acceptance suite)**:
   - Covers mock 6-source SSE search orchestration (`conc=1,4,8`), mock 7.79 MiB FLAC download + FFmpeg PCM verification + Range `206` audio stream, transport SSRF/multi-IP fallback/DNS rebinding/header stripping/pool revision drain, helper traversal & proxy-DNS checks, and goroutine/temp-dir cleanliness.

---

## 3. Python vs Go Benchmark & Short Steady-State Soak

Measured on Windows (`Python 3.12.10` `.venv` vs fresh `CGO_ENABLED=0` Go binary `16,720,384` bytes built from current source):

### 3.1 Baseline Comparison (`--sample-interval-ms 50`)

| Metric | Python Baseline | Go Binary | Delta |
|---|---|---|---|
| Cold start (`ms`) | `1030.49` | `531.74` | `-48.4%` |
| Warm start (`ms`) | — | `526.21` | — |
| Idle RSS (`MiB`) | `78.45` | `18.60` | **`-76.3%`** |
| Peak RSS (`MiB`, 50ms sampling) | `83.22` (`8` samples) | `23.47` (`5` samples) | **`-71.8%`** |
| `GET /api/config` p50 / p95 (`ms`) | `21.841` / `35.288` | `4.417` / `26.470` | `-79.8%` p50 / `-25.0%` p95 |
| Local Range stream (`MiB/s`) | `130.49` | `177.18` | `+35.8%` |

### 3.2 100k Asset Library Benchmark (`go`)

- `library_100k_pages_sampled`: `30` (all non-empty)
- `library_100k_page_p50_ms`: `13.816` ms
- `library_100k_page_p95_ms`: `26.177` ms
- `library_100k_filter_p95_ms` (`folder=artist_001`, `query=Track 0500`, `sort=updated_at`): `164.140` ms
- `library_100k_rss_mib`: `68.99` MiB

### 3.3 Short Steady-State Bounded-Concurrency Soak (`--soak-seconds 60 --sample-interval-ms 100`)

> **Explicit Notice**: This is a `60s` `short_steady_state` run (`concurrency=4`), **NOT** a `24h` endurance test. `24h` endurance was not executed.

- `sample_interval_ms`: `100` (`sample_count`: `550`)
- `total_requests`: `10,227`, `error_count`: `0` (`error_rate`: `0.0`)
- `request_p50_ms`: `18.519` ms, `request_p95_ms`: `25.090` ms
- `start_rss_mib`: `23.37` -> `mid_rss_mib`: `26.09` -> `end_rss_mib`: `26.82` -> `peak_rss_mib`: `27.48` (`rss_slope_mib_per_min`: `3.452` MiB/min, flattening between mid and end as Go heap warmed up).

---

## 4. Acceptance Test Measurements (`go test`)

### 4.1 Windows (`CGO_ENABLED=0 go test -v -count=1 ./internal/helper/... ./internal/transport/... ./internal/media/... ./tests/acceptance_runtime/...`)

- `internal/helper`: **PASS** (`9.080s`; `TestSupervisorCancelKillsGrandchildProcessTree` confirmed `InJob:1` and parent/child termination with `C:\Python314\python.exe`).
- `internal/transport`: **PASS** (`0.215s`).
- `internal/media`: **PASS** (`0.888s`).
- `tests/acceptance_runtime`: **PASS** (`3.197s`):
  - Mock multi-source SSE search: Cold `first=6.75ms`, `total=11.25ms`, `tracks=6`; Warm (cached) `first=2.00ms`, `total=10.54ms`.
  - `conc=1` (`16/16` ok, `0` 429): `first_p50=3.14ms`, `first_p95=4.81ms`, `total_p50=6.98ms`, `total_p95=9.26ms`.
  - `conc=4` (`16/16` ok, `0` 429): `first_p50=15.32ms`, `first_p95=18.66ms`, `total_p50=21.14ms`, `total_p95=26.57ms`.
  - `conc=8` (exceeds `ActiveSearchJobs=6` budget): `6/16` ok, `10` HTTP `429` backpressure, `0` unexpected errors.
  - Mock HTTP download + FFmpeg PCM verify + publish + Range `206` stream: `7.79 MiB` FLAC completed in `474.78ms` (`16.4 MiB/s`), `GET /api/files/audio` Range `0-65535` returned `206 Partial Content` matching disk bytes.
  - Transport hardening: Verified CGNAT `100.64.0.1`, benchmarking `198.18.0.1`, IPv6 ULA `fc00::1`, IPv4-mapped `::ffff:127.0.0.1`, mixed public+CGNAT DNS rejection, dead-first-IP (`::1` -> `127.0.0.1`) fallback, cross-origin `Authorization`/`Cookie` stripping, `InvalidateRevision` pool draining, and DNS rebinding block.
  - Helper & runtime hygiene: Verified traversal rejection in `TaggerClient.WriteTags` (Windows host lacked `SeCreateSymbolicLinkPrivilege` so OS symlink creation was skipped on Windows and tested inside Linux Docker instead), goroutines `base=12 -> after=14`, and clean `data/go/{tmp,staging}`.
  - Windows Store Python note: `.venv\Scripts\python.exe` resolves `sys.base_prefix` under `C:\Program Files\WindowsApps\PythonSoftwareFoundation.Python.3.12...` (MSIX AppExecAlias). Standard Win32 Python (`C:\Python314\python.exe`) stays cleanly inside the Windows Job Object (`InJob:1`); MSIX Store Python remains a documented Windows Job Object risk if chosen as runtime interpreter.

### 4.2 Linux `-race` (`melokeep-acceptance-linux:local`, `CGO_ENABLED=1`)

- `./internal/helper/...` after `supervisor.go` fix:
  - `TestSupervisorHelloTimeoutAndProtocolErrors`: **PASS**
  - `TestSupervisorLargeLineAndLimitsAndRedaction`: **PASS** (`0.05s`)
  - `TestSupervisorCancelKillsGrandchildProcessTree`: **PASS** (`0.24s`, `Parent:4978 Child:4979`)
  - `TestSupervisorConcurrencyCaps`: **PASS** (`0.29s`)
  - `TestRealMutagenTagger7FormatsAndBatch200`: **PASS** (`1.51s`)
- `./internal/download/...`:
  - `TestDownloadRange206_416_IfRangeAndTailTruncation`: **PASS**
  - `TestDownloadRetryPauseCancelDedupeForceNewAndAutoOrganize`: **FAIL** (`Invalid data found when processing input` from `/usr/bin/ffmpeg` on fake `sampleMP3Bytes` bytes)
  - `TestDownloadCancelDuringPublishingWaitsAndPreservesCommittedAsset`: **TIMEOUT / DEADLOCK** (`<-enteredPub` never reached because `Publish` was preceded by failed FFmpeg check)

---

## 5. Docker Packaging & Runtime Verification (`melokeep-acceptance:test`)

- **Python packages**: `mutagen 1.48.1`, `requests 2.34.2`, `socks` (`PySocks`) all import cleanly.
- **Helper scripts in container**:
  - `tagger.py`: `hello` handshake, missing-file error, and real Linux symlink rejection (`stage_path cannot be a symlink`) verified.
- **Full container boot**:
  - `GET /api/health` -> `200` `{"backend":"melokeep","engine":"melokeep-native","ok":true,"version":"1.0.0-go"}`
  - `GET /api/library/settings` -> `200` with `"tools":{"ffmpeg":true,"ffprobe":true,"fpcalc":true,"mutagen":true}`
  - `GET /` -> `200` HTML (`472` bytes)
  - `/proc/1/status` -> `Uid: 10001`, `Gid: 10001`, `CMD=/usr/local/bin/melokeep --host 0.0.0.0 --port 8765` (confirmed non-root privilege drop via `docker-entrypoint.py`).

---

## 6. Cross-OS Vet & Build Verification

- `CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go vet ./...` -> **PASS**
- `CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go vet ./...` -> **PASS**
- `CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go vet ./...` -> **PASS**
- `CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w"` -> **PASS** (`16,519,328` bytes)
- `CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w"` -> **PASS** (`15,335,584` bytes)
