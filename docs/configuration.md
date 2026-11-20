# 进阶配置与维护

[返回 README](../README.md)

MeloKeep 统一使用 `MELOKEEP_*` 环境变量、`melokeep.db` 数据库和 `.melokeep-history` 操作备份目录。部署时保留音乐与数据挂载；首次启动自动完成必要的数据迁移，完成后仅使用当前名称。浏览器偏好也会一次性转存，后续只保存当前名称下的设置。

下载后自动刮削、整理、已有曲库批处理及 Navidrome 连接，见 [音乐库使用说明](music-library.md)。

## NAS 目录权限

容器默认以 `10001:10001` 运行应用。入口脚本先准备数据目录，再切换到普通用户；通常无需手动修改目录所有者。

如 NAS 共享目录只授权给特定用户，在 NAS 上执行 `id 用户名`，将实际 UID/GID 添加到 Compose：

```yaml
    environment:
      MELOKEEP_USER: admin
      MELOKEEP_PASS: '你的登录密码'
      PUID: '1000'
      PGID: '1000'
```

在 NAS 上授予该用户对 `data`、`music` 及已有子目录的读写权限，确认挂载未设为只读。保留默认启动用户，通常不要另设 Compose 的 `user`。必须指定 `user` 时，需预先修复宿主机目录权限，并与 `PUID/PGID` 一致。

入口不会递归改变已有音乐库的所有者；NAS 的 ACL 或网络共享限制需在宿主机处理。修改后执行 `docker compose up -d`，用 `docker compose logs --tail 100` 检查启动结果。

## 登录与 HTTPS

Docker 在 Compose 中配置登录账号和密码；源码运行可复制 [.env.example](../.env.example) 为 `.env`。本机回环监听可不启用登录，非回环监听必须配置账号密码。域名和 IP 无需加入白名单。

通过 HTTPS 反向代理访问时，添加 `MELOKEEP_COOKIE_SECURE: '1'`；直接 HTTP 访问使用 `0`。反向代理应保留完整路径、查询参数及音频 Range 请求头。

| 环境变量 | 用途 |
| --- | --- |
| `MELOKEEP_USER` / `MELOKEEP_PASS` | 登录账号和密码，必须同时设置 |
| `MELOKEEP_COOKIE_SECURE` | HTTP 为 `0`，HTTPS 为 `1` |
| `MELOKEEP_HOST` / `MELOKEEP_PORT` | 服务监听地址与端口，本机默认 `127.0.0.1:8765` |
| `MELOKEEP_DOWNLOAD_DIR` | 音乐目录；Docker 默认 `/music` |
| `MELOKEEP_DATA_DIR` | 数据目录；Docker 默认 `/data` |
| `MELOKEEP_CONFIG` | 引擎配置文件；Docker 默认 `/data/config.json` |
| `MELOKEEP_MAX_DOWNLOAD_TASKS` | 下载并发数 `1–4`，默认 `2` |
| `MELOKEEP_ALLOW_DOCKER_SELF_UPDATE` | 是否允许 Web 端通过 Docker Socket 执行单容器一键更新；挂载 `docker.sock` 时默认启用，设为 `0` / `false` 可禁用 |
| `MELOKEEP_DOCKER_SOCKET` | 自定义 Docker Socket 路径或地址，默认 `/var/run/docker.sock` |
| `MELOKEEP_UPDATE_WEBHOOK_URL` / `MELOKEEP_UPDATE_WEBHOOK_TOKEN` | 可选外部更新 Webhook（如 Watchtower HTTP API）地址与 Bearer Token |
| `PUID` / `PGID` | Docker 应用用户和组，默认 `10001:10001` |

## Web 单容器一键更新

如希望直接在 Web「设置 → 版本与更新」一键拉取新镜像升级，且不额外部署 Watchtower 等伴生容器，只需在 `docker-compose.yml` 取消挂载宿主机 Docker Socket 的注释并重建一次容器：

```yaml
    volumes:
      - ./music:/music
      - ./data:/data
      - /var/run/docker.sock:/var/run/docker.sock
```

- **权限与安全机制**：容器入口脚本在以非 root（`PUID:PGID`）降权启动时会自动保留 `docker.sock` 对应的附加组 GID；更新检测与执行接口（`GET /api/system/update/status`、`POST /api/system/update/apply`）仅对已登录的管理员开放。
- **平滑替换与回滚保护**：触发一键更新后，系统由内置 `melokeep self-update-helper` 拉取最新镜像、校验配置并重建新容器；若新容器启动异常则自动回滚旧容器，更新完成待 `/api/health` 恢复就绪后前端自动刷新页面。
- **替代方式与禁用**：若出于环境安全策略不挂载 `docker.sock`，仍可通过配置 `MELOKEEP_UPDATE_WEBHOOK_URL`（可选配 `MELOKEEP_UPDATE_WEBHOOK_TOKEN`）接入外部更新 Webhook，或手动执行 `docker compose pull && docker compose up -d`；如需显式关闭 Docker 自更新，可设置 `MELOKEEP_ALLOW_DOCKER_SELF_UPDATE: '0'`。

## 机器人配置

优先在网页 **设置 → 通知与机器人** 中配置，保存后覆盖对应环境变量。Token、Secret 等凭据不回显，留空保留原值；不要把实际凭据提交到仓库。

公网地址支持 HTTP / HTTPS 域名或 IP，可带端口，如 `http://192.168.1.20:8765`、`https://music.example.com`；省略协议时自动补 `http://`，不含路径或查询参数。填写手机可访问的地址；企业微信回调还需平台可访问。下载链接默认有效 24 小时，可设为 60–604800 秒。

连接测试使用当前填写的渠道凭据和代理，无需先保存，也不要求先启用或填写公网地址、白名单。测试不保存配置、不发送消息；保存后配置才会生效。内置 Telegram Local Bot API 的首次测试是例外：如果内置服务尚未按当前 API ID/API Hash 运行，测试只检查程序存在和凭据格式，并提示保存后启动服务；服务按这组凭据运行后再次测试才会实际调用 Telegram。更多基础说明见 [README](../README.md)。

### Telegram

需要通过聊天使用 AI 时，先配置「设置 → AI 助手」，再在通知设置勾选对应渠道的「使用 AI 对话」。Telegram、企业微信、飞书 / Lark、钉钉及 Discord 均可接入。AI 默认关闭，原有搜索、下载、翻页命令始终可用；未配置 AI 时普通歌名仍走原搜索流程。详见 [渠道 AI 对话与命令](ai-assistant.md#在-telegram--企业微信中使用)。

1. 在 BotFather 创建机器人，将 Token 填入网页。
2. 填写允许使用者的数字用户 ID，多个用逗号分隔，不能填用户名或群 ID。
3. 按需选择“使用项目代理”，测试连接后启用并保存。
4. 与机器人私聊发送 `/start` 或 `/menu`，也可直接发送歌名。

使用长轮询接收命令，无需公网回调。如果此前配置过 webhook，先移除。一个 Token 只运行一个服务实例。

Telegram API 地址留空恢复官方地址。本机 `localhost`、`127.0.0.1` 和 `::1` 上的自建 API 自动绕过外部代理，其他地址按所选代理模式连接。API 地址应直接提供服务，接口重定向会被拒绝并显示错误。超长消息或同一用户频繁操作会收到提示，不阻塞后续消息；命令队列满时保留未接收的消息等待重试。

### 企业微信

1. 创建自建应用，配置可见成员，将 CorpID、AgentID、Secret 填入网页。
2. 在企业微信后台配置服务器出口的可信 IP。
3. 生成消息回调 Token 和 EncodingAESKey，填入网页后启用并保存。
4. 在企业微信后台将接收消息 URL 设为 `https://music.example.com/bot/wecom`，填写相同的 Token、EncodingAESKey，完成验证。
5. 打开应用会话发送“帮助”。白名单使用成员 UserID；留空允许应用可见成员。

这里只支持成员与应用的直接会话。企业微信平台要求的可信 IP 与本项目已移除的访问主机白名单无关。

本地公网 IP 经常变化时，可在“企业微信 API 转发地址”填写自建 VPS 的 HTTPS 地址，例如 `https://wx.example.com` 或 `https://wx.example.com:8443`。留空恢复官方 `https://qyapi.weixin.qq.com`。地址不包含 `/cgi-bin` 路径；转发服务需保留原请求的 `/cgi-bin/*` 路径、查询参数、请求体和文件上传。获取 Token、发送消息、素材上传和图片下载均走该地址，企业微信应用的可信 IP 填 VPS 的实际公网出口 IP。也可设置 `MELOKEEP_WECOM_API_URL`，网页保存值优先；该字段清空并保存可恢复官方直连。

这是一种 API 反向代理地址，不是 HTTP/SOCKS 正向代理。转发服务会接触 Secret、access_token 及消息内容，只配置自己的或信任的服务。请求不会跟随重定向，请使用证书有效、直接提供转发的 HTTPS 地址。

接收消息是独立方向：`企业微信 → 公网访问地址/bot/wecom → MeloKeep`。API 转发并不会自动转发此回调；MeloKeep 在 NAS 上时，还需让回调域名通过反向代理或隧道到达 NAS，保留 GET 验证查询参数和 POST 消息体。更改 Docker 映射端口不会修改已保存的“公网访问地址”。“测试企业微信连接”只获取 Token，不发送消息，也不证明回调可达；回调需在企业微信后台保存验证。

### 命令与下载链接

| 操作 | 命令 |
| --- | --- |
| 菜单 | `帮助`、`菜单`、`/start`、`/menu` |
| 搜索 | 直接发送歌名，或 `/search 歌名 歌手` |
| 翻页 | `下一页`、`上一页`、`第2页` |
| 下载 | `下载 2` 或 `/download 2`，按搜索结果编号选择 |
| 查看进度 | `状态` 或 `/status` |
| 管理任务 | `取消 任务编号`、`继续 任务编号`、`链接 任务编号` |

Telegram 也可点击消息按钮操作。搜索结果分页展示，Telegram 每页 8 首，其他渠道每页 5 首。

机器人与网页共用下载队列。Telegram 的 MP3 / M4A / FLAC 优先使用 `sendAudio`；FLAC 上传时保留原文件名和字节，并标记 `audio/flac`，客户端支持时可在聊天内播放。音频格式被明确拒绝后才改用 `sendDocument`，不转码。文件采用流式上传，不把整个附件读入内存，上传期间不阻塞搜索卡片更新。Telegram 文件均先尝试发送，超过 50 MB 也不提前拦截；接口明确拒绝文件大小后回退到签名下载链接，并在日志和通知中显示实际错误。已拒绝上传的文件在链接通知重试时不会重复上传。企业微信仍使用临时素材发送文件，超过 20 MB 时提供链接。网络错误保留通知重试，不重新下载曲目；`/file 任务编号` 可重新尝试发送文件，`/link 任务编号` 可获取链接。

Telegram 文档标注云端新文件发送上限为 50 MB，官方 [Local Bot API Server](https://core.telegram.org/bots/api#using-a-local-bot-api-server) 的 `--local` 模式为 2000 MB。通知设置中可以直接开启“使用内置 Telegram 本地服务（大文件发送）”，填写从 [my.telegram.org](https://my.telegram.org/apps) 获取的 API ID 和 API Hash；Docker 镜像会内置官方程序并自动管理本机 8081 端口。也可以关闭内置模式，填写自建 API 地址（例如 `http://telegram-bot-api:8081`，或环境变量 `MELOKEEP_TG_API_URL`）。本项目不会按这两个数值提前拒绝文件，以实际接口响应为准。切换服务器时，只有相同 Bot Token 更换 API 地址才会调用旧服务的 `logOut`；内置服务先启动成功再退出旧服务，更换机器人 Token 不会退出旧机器人。旧 API 不可达时仍可保存新配置，并在日志提示手动完成迁移；其他程序占用 Token 或环境变量首次部署时，请按 [README](../README.md) 和[官方迁移说明](https://core.telegram.org/bots/api#moving-a-bot-to-a-local-server)处理。本地服务只监听回环地址，不需要与 MeloKeep 共享音乐目录。仅使用 Telegram 文件交付时访问地址可留空，大小被拒绝后的下载链接需要配置访问地址。实测时可在“运行日志 → 通知与机器人”查看易懂的失败原因，展开“查看原始信息”可保留平台错误。

### 飞书、钉钉、Discord 下载通知

在“更多下载通知渠道”填入平台生成的 HTTPS 群 Webhook 并启用。飞书 / Lark 与钉钉可填写加签密钥；如果使用关键词校验，请允许 `MeloKeep`。Webhook 为凭据，界面不回显；留空保留原值，可勾选清除。配置检查只校验地址和格式，不向群发送测试消息，实际权限及平台安全设置在首次通知时验证。

Discord 长通知会拆分正文并为下载链接预留长度，保留完整链接及 Unicode 字符。所有 Webhook 请求均拒绝重定向；使用平台直接生成的地址。通知失败保存在本地队列并重试，平台加签失败、权限不足或限流会记录在“运行日志 → 通知与机器人”。

通知启用后产生的新下载任务的完成、失败、取消状态，覆盖 Web 与私聊下载；首次启用不会推送历史任务。完成通知附签名下载链接，访问地址须能被群成员访问，链接持有者可以下载，请控制群成员范围与有效期。Webhook 通道仅通知结果，无需 AI 配置。飞书 / Lark、钉钉、Discord 可在「更多对话机器人」另配应用凭据和用户白名单，独立接收搜索、下载命令及可选 AI 对话；不用改变原来的 Webhook。配置、平台权限与回调步骤见 [更多对话渠道](ai-assistant.md#在飞书钉钉discord-中使用)。

消息交互按平台能力区分：Telegram 私聊使用按钮；企业微信、飞书 / Lark、钉钉和 Discord 应用机器人使用回复文字命令，例如 `下载 1`、`音源 2`、`下一页`、`状态`。非 Telegram 应用消息中的任务通知显示完整任务编号，可直接回复 `链接 <完整任务编号>`、`文件 <完整任务编号>` 或 `取消 <完整任务编号>`。群 Webhook 只推送下载状态和链接，需要操作时请回到对应的应用机器人或网页。

`/downloads/` 和 `/bot/wecom` 使用各自的签名鉴权，反向代理不要在这些路径增加额外登录验证。透传 Range / If-Range，不缓存下载响应；链接持有者在有效期内可以下载对应文件，不要公开转发链接。

保留 `data` 目录以保留任务归属、待发送通知和链接密钥。运行单个 Go 服务进程，确保任务调度与长轮询单实例运行。

## 高级配置

普通使用无需展开高级配置。每个字段填写独立 JSON 对象：

| 字段 | 用途 |
| --- | --- |
| `init_music_clients_cfg` | 初始化参数，覆盖对应全局参数 |
| `clients_threadings` | 并发线程数 |
| `requests_overrides` | 请求头、超时、独立代理等请求参数 |
| `search_rules` | 搜索与解析规则参数 |

例如，设置 20 秒请求超时，可在请求覆盖中按需填写 `{"timeout": 20}`。旧版分项代理优先于项目代理，可在设置面板切换为项目代理或直连。

## 下载与故障排查

### 搜索速度与运行占用

网页搜索默认在返回结果时展示格式、音质和文件大小。机器人也会在全部检索完成前显示已有结果，已展示的下载编号不再随排序改变。

在「设置 → 引擎行为」中可开启「按需解析」，让网页搜索先显示候选歌曲，点击播放或选择下载版本时才解析音频地址；此模式下格式、音质和大小可能暂不可见，音质显示为“待确认”。关闭开关恢复搜索时解析。保存后对下一次搜索生效，无需重启，重启后仍保留选择。

网页保存的选择优先于 `MELOKEEP_LAZY_SEARCH`；尚未保存选择时使用环境变量，未设置则默认关闭。之前通过环境变量开启的部署也可直接在网页中关闭并保存。

搜索进程按需创建，最多保留两个空闲进程供后续任务复用，默认空闲 20 秒回收。取消或超时会终止正在执行该任务的进程；相同查询的多个订阅者共享任务，只有最后一个订阅者退出才会取消。网页断开后保留 10 秒重连时间，创建后 30 秒未订阅的搜索会自动停止。

成功且非空的查询默认缓存 30 秒，缓存同时受 32 条查询和 8 MiB 序列化数据限制。空结果和失败不会缓存。网页连续提交同一个关键词会强制刷新；API 调用者可以在 `POST /api/search/jobs` 中传 `refresh: true`。缓存只短期复用解析结果，外部音频链接仍可能提前失效。

以下环境变量在服务重启后生效；Docker 部署填入 Compose 的 `environment`，本机运行填入 `.env`：

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `MELOKEEP_SEARCH_WORKERS` | `4` | 同时执行的搜索/按需解析数，范围 1–6；低内存 NAS 可试 2 |
| `MELOKEEP_SEARCH_WORKER_IDLE` | `20` | 空闲进程保留秒数；0 表示每个任务结束即退出 |
| `MELOKEEP_SEARCH_BUDGET` | 自动，至少 `120` | 单音源搜索或按需解析的执行秒数，不含排队；自动值同时保证比该源请求超时多 30 秒，显式设置则使用指定值 |
| `MELOKEEP_SEARCH_QUEUE_TIMEOUT` | 自动，至少等于执行预算 | 等待搜索槽位的最长秒数；显式设置可缩短或延长，避免多音源搜索因后续音源尚未开始就超时而缺结果 |
| `MELOKEEP_SEARCH_CACHE_TTL` | `30` | 查询缓存秒数；0 关闭已完成结果缓存，仍合并正在执行的相同查询 |
| `MELOKEEP_SONG_CACHE_MB` | `32` | 完整曲目的内存缓存预算，按序列化大小估算，同时最多 3000 首；大曲目保留磁盘副本，按需读取 |
| `MELOKEEP_LAZY_SEARCH` | `0` | 未在网页保存选择时的默认值；1 开启网页搜索按需解析，0 搜索时解析并展示格式、音质和可用的大小 |

网页设置里的请求超时仍是单次网络请求超时，与上述总搜索预算不同。运行日志记录排队时间、执行时间和首条增量时间。

下载进度文件最多约每秒更新，运行中的进度数据库提交最多约每两秒一次；完成、失败和暂停等状态立即保存。网页有活动下载时约每 1.5 秒刷新，无活动任务时约每 15 秒刷新，后台标签页暂停下载列表请求。歌单解析池限制为一个进程，定期更换进程。曲目缓存预算不是整个服务的内存上限，进程、元数据和活动任务仍占用额外内存。

离线测量重复启动开销：`python tests/benchmark_search.py`。

暂停保留临时数据；取消清理临时文件。HTTP 支持 Range 且文件校验通过时可续传，否则从头下载。移除下载记录保留已完成文件，音乐文件页的删除则会删除磁盘文件。

下载队列可按等待、验证资源、正在下载、保存标签与歌词、已完成、失败、已暂停、已取消筛选，并显示各状态数量。全选、批量暂停/继续/取消、清理已结束记录均只作用于当前筛选可见的任务；切换筛选会清空选择。

歌单导入通过各原生音源接口实现，导入时就会解析音频地址，并保存完整的曲目解析结果。地址有效期由音源或解析接口决定，可能还依赖 Cookie、请求头、IP 或平台权限，项目没有统一的有效时长。曲目记录的 60 秒解析缓存不代表地址能用 60 秒，也不会在到期时删除导入的歌曲。这里的音源地址与机器人通知中默认有效 24 小时的本机文件签名链接不同。

下载失败后会先尝试重新解析原音源的同一曲目，再按配置顺序从其他已启用音源搜索匹配。仅接受规范化后歌名、歌手相同、版本一致、已知时长差不超过 5 秒或 2% 的资源，优先同专辑；每源最多搜索 5 条，尝试前两个匹配结果。复用各音源现有 Cookie、代理和请求设置，不启用未配置的音源。换源使用独立临时文件，成功后在队列显示实际音源与音质（可能不同于原版本），保留原歌单曲目身份；尝试过程记录在运行日志中。全部失败则保留失败状态供检查与重试。

整理或重命名成功、音频与附属文件迁移完成后，会自动逐层删除来源路径中的空文件夹；只删除真正为空的目录。音乐根目录、音源工作根目录、整理库根目录、已启用的监控根目录和内部任务根目录会保留，有其他文件或无删除权限的目录也会保留。撤销整理时会重新创建原路径并恢复备份。

搜索、播放或通知失败时，在 **运行日志** 中按类别和错误级别筛选并导出。日志保留当前进程最近 800 条，重启后清空；Windows 启动问题查看 `logs/startup.log`。

试听和下载始终验证 TLS 证书，证书异常会报告失败，不再自动跳过验证。音源资源、封面和 HLS 分片仅允许公网地址，逐次验证跳转并固定实际连接的 IP；跨站跳转会移除账号凭据。音源请求只使用显式配置的代理，HTTP 代理需要支持 CONNECT。Navidrome 和 Telegram 的显式内网服务配置继续支持。

依赖请确保安装 `Go 1.27+`、`Node.js 24+`，以及运行时辅助工具 `Python 3.11+`（仅需 `mutagen`、`requests[socks]`、`python-dotenv`）、`FFmpeg / ffprobe` 与 `Chromaprint / fpcalc`。音频标签读写通过受控 Mutagen helper 执行；搜索、下载与 JS 插件沙箱均由 Go 原生实现。

## 开发检查

```sh
npm ci --prefix frontend
npm run build --prefix frontend
npm run format:check --prefix frontend
npm test --prefix frontend
cd go && CGO_ENABLED=0 go test ./...
```

浏览器检查：在 `frontend` 目录运行 `npx playwright install chromium`，再运行 `npm run test:browser`。Windows 可通过 `MELOKEEP_BROWSER_EXECUTABLE` 指定 Chrome / Edge 路径。

源码构建 Docker：

```sh
docker build -t ghcr.io/agonie0v0/melokeep:latest .
docker compose up -d --pull never
```

推送 `main` 后，GitHub Actions 构建 `latest` 和对应提交的 `sha-...` 镜像，并检查容器启动与无冗余提取器依赖校验。仅修改 Markdown 文档或 `docs/**` 路径的提交会自动跳过 CI 与 Docker 镜像重建。发布成功后再拉取更新。
