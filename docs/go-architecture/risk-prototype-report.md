# T01 技术风险样机验证报告

验证时间：2026-11-14。环境：Windows 11 (`windows/amd64`)、Go `1.27.4` (`CGO_ENABLED=0`)、FFmpeg `2025-12-28` (`E:\tool\ffmpeg\bin\ffmpeg.exe`)、Python 3.13 + `mutagen 1.48.1`。
样机模块：`go/risks/`（独立 `go.mod`，不影响主模块边界）。运行命令：`go test -v ./...`（全部 6 项通过，耗时 9.32s）。

## 1. 验证结论摘要

| 风险项 | 状态 | 实测关键指标与发现 | 对后续任务 (T02–T07) 的建议 |
| --- | --- | --- | --- |
| 1. 纯 Go SQLite (`modernc.org/sqlite v1.62.0`) | **通过** | `WAL` / `foreign_keys=ON` / `synchronous=FULL` 生效；1 写 + 4 读并发零冲突；10 万行写入 5.18s (35.7 MiB)；深层 keyset 分页 100 条 p95 = **1.01 ms**；歌手过滤分页 p95 = **1.01 ms** | T03 正式采用 `modernc.org/sqlite` + `CGO_ENABLED=0`；写池 `MaxOpenConns=1`，读池 `MaxOpenConns=2..4` + `query_only(ON)` |
| 2. 进程树清理 (Windows Job Object / Unix) | **通过** | `CreateJobObject` + `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` + `CREATE_SUSPENDED` + `AssignProcessToJobObject` + `ResumeThread`，实测同时杀死父进程与孙进程耗时 **2.36 ms**；`linux/amd64` 与 `linux/arm64` `go vet` 通过 | T04 Windows 实现**不要**传 `CREATE_BREAKAWAY_FROM_JOB`（在嵌套 Job 环境下会触发权限拒绝），使用 `CREATE_SUSPENDED` 绑定后恢复主线程；Linux 使用 `Setpgid=true` + `Pdeathsig=SIGKILL` |
| 3. 公网 HTTP Transport 与连接池隔离 | **通过** | 严格模式拦截 `127.0.0.1`、`10.0.0.5`、`169.254.169.254`、`::1`；环回测试证明 IP 固定下 TLS SNI / `Host` 保留、`https->http` 降级拒绝、跨站清除 `Authorization`/`Cookie`/`Referer`、按 `configRevision` 隔离连接池 | T04 照此结构实现三类信任域，确保 `Proxy: nil` 避免静默读取环境变量，配置变更后调用 `InvalidateRevision` 排空旧连接 |
| 4. HTTP Range 与 HLS AES-128 / MAP / BYTERANGE | **通过** | `200` / `206` / `416` (`bytes */total`) / `If-Range` 不匹配回退完整下载校验一致；`#EXT-X-MAP` + `BYTERANGE` + AES-128-CBC + PKCS7 解密后经 FFmpeg 封装与全流解码零报错 | T08 直接复用相同检查点原子写与 Range 状态机，HLS 顺序或小有界预取即可 |
| 5. 多格式标签写入与 `batch_read_tags` | **通过** | MP3/FLAC/M4A/WAV/Ogg/Opus/WMA 全部 7 种格式写入 17 个文字标签 + 中文歌词 + PNG 封面，写回前后 FFmpeg PCM SHA-256 **完全一致**；`batch_read_tags` 200 文件（含 1 个损坏文件隔离报错）耗时 **251.85 ms** | **重要发现**：Windows 下 Python 默认管道编码为系统代码页（如 `cp936`），会导致 UTF-8 中文编码报错。T04 启动 Python helper 时必须同时注入 `PYTHONUTF8=1`、`PYTHONIOENCODING=utf-8`，并在脚本入口执行 `reconfigure(encoding='utf-8')` |
| 6. `library_rules.py` 表达式与中文处理 | **已核对（待 T09 实现）** | `ast.parse` 白名单语法边界清晰，含 `pypinyin` 首字母与 `opencc` 繁简转换 | T09 在 Go 实现受限递归下降解析器 + `go-pinyin` / `opencc` 规则表，并以 Python `library_rules.py` 产出 golden 用例对照 |

## 2. 关键实现细节与环境约束

### 2.1 Go 构建与 `-race` 说明
- 当前环境默认连接 `proxy.golang.org` 超时，构建拉取依赖需配置 `GOPROXY=https://goproxy.cn,direct`。
- 当前 Windows 本机未安装 `gcc`（CGO 禁用），因此 `go test -race` 需在 Linux CI（或安装 MinGW-w64 后）执行；`CGO_ENABLED=0` 下所有测试与 `GOOS=linux GOARCH=amd64/arm64` 交叉检查均已通过。

### 2.2 TagLib vs Mutagen 结论
- 实测表明，受控 Mutagen NDJSON v1 helper 已能完整覆盖 7 种容器的 17 个字段、内嵌歌词和封面写回（音频 PCM 零改动），且 200 文件批量只读耗时约 0.25 秒（10 万文件折算约 500 次短命进程调用，或在 Go 原生支持 FLAC/MP3/M4A 只读解析后进一步降为仅兜底少数容器）。
- 因此按既定架构维持 `Mutagen` 按需 helper 即可满足 G2–G5 交付，无需在核心迁移前强制引入 C/C++ TagLib CGO 编译复杂度。

### 2.3 `library_rules.py` 移植边界（供 T09 参考）
1. **表达式 AST 白名单**：长度 `<= 1000`，节点数 `<= 100`，整数字面量绝对值 `<= 1000`，单变量及结果上限 `10000` 字符。
2. **运算符与函数**：`+` 拼接、`and`/`or`/`not`、`a if cond else b`、一元 `-const`、字符串/列表下标 `s[i]`（越界返回空字符串 `""` 而非报错）、`str()`/`int()`，以及字符串方法 `strip`/`lstrip`/`rstrip`/`replace`/`split`/`lower`/`upper`/`title`/`zfill`（最多 3 参数、无关键字参数）。
3. **变量与提取模板**：17 个 `TAG_FIELDS` + 别名（`albumartist`、`tracknumber`、`discnumber`）+ `first_artist`、`first_artist_letter`（`pypinyin` 首字母大写，空返回 `#`）、`filename`、`file_song_name`、`parent_path`、`parent_parent_path`、`year`、`counter`、`null`、`lyrics`、`ext`；`extract()` 按字面分隔符顺序切片，不支持任意正则。
4. **文本清洗**：`repair` 仅做无损 `latin1 -> utf-8` 逆转修复，失败保留原文；空白折叠 + Unicode NFC；繁简转换（`opencc` `t2s`/`s2t`）。T09 必须直接复用旧 Python 输出生成 golden fixture 校验边界行为。
