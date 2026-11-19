# MeloKeep Provider Plugin API v1 规范

MeloKeep 提供基于纯 Go ECMAScript 沙箱（`goja`）的通用音源扩展接口。通过编写标准化的单文件 JavaScript 插件，用户可以在不修改服务端源码、不重启主程序的前提下，自行接入开放许可音频库、公开播客源或自托管媒体服务。

> [!IMPORTANT]
> **合规与中立性声明**：MeloKeep 插件系统仅为通用、中立的脚本扩展运行时。项目官方仓库不提供、不托管、也不背书任何未获授权的商业音源或侵权解析插件。插件安装、配置凭据及其所访问内容的合法性均由插件开发者与最终用户自行评估并承担责任。

---

## 1. 模块导出结构

每个插件为单个 UTF-8 编码的 `.js` 文件，采用 CommonJS 导出形式：

```js
module.exports = {
  manifest,
  search,
  resolve,
  playlist, // 可选：capabilities.playlist === true 时调用
  episodes, // 可选：capabilities.episodes === true 时调用
  lyrics,   // 可选：capabilities.lyrics === true 时调用
  metadata, // 可选：capabilities.metadata === true 时调用
};
```

- `manifest`、`search`、`resolve` 为核心必需导出项（当声明 `capabilities.search: true` 时必须提供 `search` 函数）。
- 可选能力函数仅在 `manifest.capabilities` 中对应开关为 `true` 时生效。
- 所有导出的处理函数均支持**同步返回值**或 **Promise 风格逻辑**（注：沙箱内 `ctx.http.*` 为同步阻塞受控调用，插件无需额外引入外部网络库）。

---

## 2. `manifest` 元数据规范

```js
const manifest = {
  apiVersion: 1,
  id: "open-podcast-rss",
  name: "公开播客 RSS",
  version: "1.0.0",
  description: "解析标准公开 Podcast RSS / Atom 订阅源",
  category: "播客与有声",
  capabilities: {
    search: true,
    playlist: false,
    episodes: true,
    lyrics: false,
    metadata: false,
  },
  settings: [
    {
      key: "feed_url",
      label: "RSS 订阅地址",
      type: "url",
      placeholder: "https://feeds.example.org/podcast.xml",
      required: true,
      defaultValue: "",
    },
  ],
};
```

### 字段校验规则

| 字段 | 类型 | 必选 | 说明 |
| --- | --- | --- | --- |
| `apiVersion` | `number` | 是 | 固定为 `1`。 |
| `id` | `string` | 是 | 唯一插件标识符，必须匹配正则 `^[a-z0-9][a-z0-9_-]{1,47}$`（2–48 位小写字母、数字、`_` 或 `-`，首字符为小写字母或数字），且**不得与任何内置音源 ID 或 `EngineName` 冲突**。 |
| `name` | `string` | 是 | 前端与客户端展示的音源名称（1–64 字符）。 |
| `version` | `string` | 是 | 插件版本号（推荐 SemVer，如 `"1.0.0"`）。 |
| `description` | `string` | 否 | 简短描述，展示在插件管理与音源列表。 |
| `category` | `string` | 否 | 分类标签，如 `"开放许可"`、`"播客与有声"`、`"自托管源"`。 |
| `capabilities` | `object` | 是 | 包含 5 个布尔开关：`search`、`playlist`、`episodes`、`lyrics`、`metadata`。 |
| `settings` | `array` | 否 | 声明用户可在界面填写的配置表单字段列表。 |

### `settings` 表单字段定义

每项配置对象包含：

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `key` | `string` | 参数键名，在运行时通过 `ctx.settings[key]` 读取。 |
| `label` | `string` | 表单展示标签。 |
| `type` | `string` | 输入类型：`text`、`password`、`url`、`number`、`boolean`。标记为 `password` 或包含敏感关键词的字段在接口回显时由服务端自动脱敏。 |
| `placeholder` | `string` | 输入框占位提示文本。 |
| `required` | `boolean` | 是否必填。 |
| `defaultValue` | `string` | 默认值（字符串形式）。 |

---

## 3. 沙箱上下文工具箱 `ctx`

每次调用插件方法时，宿主向第 1 个参数注入隔离且只读的 `ctx` 工具箱对象：

### 3.1 `ctx.http`（受控网络请求）

所有网络请求强制经由 MeloKeep 统一安全 `transport` 执行，遵守全局与插件级代理策略、TLS 证书校验与响应体大小上限。

- **`ctx.http.request(req)`**：发起单次 HTTP 请求。
  - 请求参数 `req`：
    ```ts
    {
      method?: "GET" | "POST" | "PUT" | "HEAD" | "DELETE"; // 默认 "GET"
      url: string;
      headers?: Record<string, string>;
      body?: string;
      timeoutMs?: number;       // 受单次调用总预算 15s 约束
      followRedirects?: boolean; // 默认 true，跨站跳转自动剥离 Cookie/Authorization
    }
    ```
  - 返回值：`{ status: number, headers: Record<string, string>, body: string, finalUrl: string }`
- **`ctx.http.batch(reqs)`**：批量并发发起一组 HTTP 请求。
  - **单批上限**：`reqs.length <= 8`（超出将立即报错）。
  - **单次调用总请求上限**：单次插件方法调用（如一次 `search` 或 `resolve`）期间，所有 `ctx.http.request` 与 `ctx.http.batch` 累计发起的 HTTP 请求总数 **不得超过 24 次**。
  - 返回与 `reqs` 等长的响应数组（每项包含 `{ status, headers, body, finalUrl, error? }`）。
- **SSRF 防护与白名单机制**：
  - 默认策略**强制校验目标解析出的所有 IP 均为公网 IP**（阻止 `127.0.0.0/8`、`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、`169.254.0.0/16`、`100.64.0.0/10`、`::1`、`fc00::/7` 等私网/保留地址及 DNS Rebinding 攻击）。
  - 若插件用于访问自托管内网媒体服务（如局域网 NAS 上的私有服务），必须由管理员在插件设置中显式配置授权的 `allowed_origins`（如 `http://192.168.1.50:8080`）白名单后方可放行对应 Origin。

### 3.2 `ctx.crypto`（密码学工具集）

纯 Go 密码学实现，避免在 JS 层低效模拟或引入原生模块：

| 方法 | 说明 |
| --- | --- |
| `ctx.crypto.md5(data)` | 返回小写十六进制 MD5 摘要字符串。 |
| `ctx.crypto.sha1(data)` | 返回小写十六进制 SHA-1 摘要字符串。 |
| `ctx.crypto.sha256(data)` | 返回小写十六进制 SHA-256 摘要字符串。 |
| `ctx.crypto.hmacSha1(key, data)` | 返回小写十六进制 HMAC-SHA1 字符串。 |
| `ctx.crypto.hmacSha256(key, data)` | 返回小写十六进制 HMAC-SHA256 字符串。 |
| `ctx.crypto.aesEncrypt(opts)` | AES 加密（支持 `{ key, iv, mode, padding, data, outputEncoding }`，mode 如 `CBC`/`ECB`/`GCM`）。 |
| `ctx.crypto.aesDecrypt(opts)` | AES 解密（支持 `{ key, iv, mode, padding, data, inputEncoding }`）。 |
| `ctx.crypto.rsaEncrypt(opts)` | RSA 公钥加密（`{ publicKeyPem, padding, data, outputEncoding }`）。 |
| `ctx.crypto.randomBytes(n)` | 生成 `n` 字节密码学安全随机数据（返回十六进制字符串，单次长度受限）。 |

### 3.3 `ctx.encoding`（编码与解码）

- `ctx.encoding.base64.encode(str)` / `ctx.encoding.base64.decode(str)`
- `ctx.encoding.hex.encode(str)` / `ctx.encoding.hex.decode(str)`
- `ctx.encoding.url.encode(str)` / `ctx.encoding.url.decode(str)`

### 3.4 `ctx.html`（轻量 HTML / XML 文本工具）

- `ctx.html.stripTags(html)`：剥离 HTML/XML 标签并规范化空白字符，返回纯文本。
- `ctx.html.unescape(text)`：解码 HTML 实体（如 `&amp;`、`&quot;`、`&#39;`）。
- `ctx.html.findText(html, tagOrSelector)`：提取首个匹配标签或简单选择器的文本内容。
- `ctx.html.findAttr(html, tagOrSelector, attrName)`：提取首个匹配元素的指定属性值。

### 3.5 `ctx.settings` & `ctx.cookie`

- **`ctx.settings`**：当前插件实例的用户配置映射（键为 `manifest.settings[i].key`，值为字符串，未填写时自动回落到 `defaultValue`）。
- **`ctx.cookie`**：当前插件专属、与其他音源完全隔离的 Cookie 读取接口（`ctx.cookie.get(name)`、`ctx.cookie.header()`）。插件无法读取其他音源或宿主的 Cookie。

### 3.6 `ctx.emit(track)` & `ctx.log(level, msg)`

- **`ctx.emit(track)`**：在 `search` 与 `playlist` 执行期间实时推送候选曲目到 SSE 搜索流或导入结果。当用户取消搜索或达到数量上限时，`ctx.emit` 会抛出终止信号，插件不应吞掉该中断异常。
- **`ctx.log(level, msg)`**：输出运行日志，支持 `"debug"`、`"info"`、`"warn"`、`"error"`。所有日志输出在写入缓冲前均会自动对已知密钥和 Cookie 执行脱敏。

---

## 4. 钩子方法签名与数据结构

### 4.1 `search(ctx, opts)`

- 输入 `opts`：`{ keyword: string, limit: number, refresh: boolean }`
- 通过 `ctx.emit(track)` 推送候选对象：
  ```js
  ctx.emit({
    sourceId: "track-001",        // 必需：该插件内稳定唯一的曲目标识
    title: "Solar Wind",          // 必需：标题
    artist: "Open Collective",    // 必需：艺术家
    album: "Creative Sessions",   // 可选：专辑名
    duration: 198,                // 可选：时长（秒，浮点数或整数）
    cover: "https://...",         // 可选：封面图片 URL
    episodes: 0,                  // 可选：合集/播客集数
    variants: [                   // 可选：已知音质列表
      {
        variantId: "mp3:320",
        label: "MP3 320kbps",
        codec: "mp3",
        bitrate: 320,
        lossless: false,
        availability: "available",
        evidence: "api",
        previewOnly: false,
      },
    ],
  });
  ```
- 返回值（可选）：`{ count?: number, detail?: string }`。

### 4.2 `resolve(ctx, track, policy)`

- 输入：
  - `track`：`{ publicId, source, sourceId, title, artist, album, duration, cover, episodes, variants }`
  - `policy`：`{ preferredVariantId, preferredQuality, allowPreview }`
- 返回值：已解析媒体列表（`ResolvedMedia[]`，**严禁包含内网地址**，仅用于服务端当次受控流式代理与下载，不持久化到长期存储）：
  ```js
  return [
    {
      variantId: "flac:999",
      label: "FLAC",
      codec: "flac",
      bitrate: 999,
      lossless: true,
      previewOnly: false,
      protocol: "http",
      url: "https://cdn.example.org/audio/track-001.flac",
      headers: { "User-Agent": "MeloKeep-Plugin/1.0" },
      sizeBytes: 24576000,
      duration: 198,
      lyrics: "",
    },
  ];
  ```
  > 若音源仅提供官方片段试听（如 30 秒试听），必须显式设置 `previewOnly: true`。

### 4.3 `playlist(ctx, locator)`（可选）

- 输入：`locator`（歌单链接或 ID 字符串）。
- 使用 `ctx.emit(track)` 推送歌单内曲目，可附带 `playlistIndex`。
- 返回：`{ id, name, count, detail, issues: [{ sourceId, title, artist, reason }] }`。

### 4.4 `episodes(ctx, track)`（可选）

- 输入：父级播客/有声书候选 `track`。
- 返回：`Track[]`（每个分集曲目数组）。

### 4.5 `lyrics(ctx, track)`（可选）

- 输入：`track`（包含 `sourceId`、`title`、`artist`、`album`）。
- 返回：`string`（优先 LRC 带时间轴格式，或纯文本歌词；无歌词返回 `""`）。

### 4.6 `metadata(ctx, opts)`（可选）

- 输入 `opts`：`{ query, title, artist, album, limit }`
- 返回：`MetadataItem[]`，供曲库刮削工作台与候选对比使用：
  ```js
  return [
    {
      sourceId: "meta-001",
      title: "Solar Wind",
      artist: "Open Collective",
      album: "Creative Sessions",
      albumArtist: "Open Collective",
      date: "2026-03-15",
      trackNumber: "1",
      discNumber: "1",
      duration: 198,
      coverUrl: "https://cdn.example.org/cover.jpg",
      lyrics: "[00:00.00]Instrumental",
    },
  ];
  ```

---

## 5. 安全边界与硬性资源限制

为保障自托管服务器及 NAS 环境安全，MeloKeep 插件运行时强制执行以下隔离与资源熔断机制：

1. **源码体积上限**：单插件 JavaScript 源码不得超过 **512 KiB**。
2. **硬超时中断**：每次插件方法调用（`search`、`resolve`、`playlist`、`episodes`、`lyrics`、`metadata`）限时 **15 秒**，超时后通过 Goja VM Interrupt 机制强制硬中断，防止死循环或长尾阻塞。
3. **零宿主权限**：沙箱中彻底移除且不提供 `fs`、`path`、`os`、`child_process`、`process.env`、`require()`、原生模块加载及任意 socket 能力。
4. **HTTP 请求配额**：`ctx.http.batch` 单批最多 **8 个**请求；单次插件调用累计最多 **24 个** HTTP 请求。
5. **公网隔离与 SSRF 防护**：默认拒绝全部内网/环回/保留 IP 及可疑重定向；管理员仅可通过各插件独立的 `allowed_origins` 显式放行信任的内网地址。
6. **源码哈希校验与显式启用**：上传或远程 URL 拉取的插件均计算 `sha256` 摘要并保存审计状态；支持在不注册生效前执行 `inspect` 静态检查与单方法 `test` 沙箱测试。

---

## 6. 参考示例

仓库内置两个开箱即用的合规参考插件：

- [mock-provider.js](examples/mock-provider.js)：零外网依赖的本地演示插件（演示 `manifest`、`search` + `ctx.emit`、`resolve`、`lyrics`、`metadata`），适合离线验证插件沙箱、搜索流与刮削界面联调。
- [open-podcast-rss-provider.js](examples/open-podcast-rss-provider.js)：标准公开 Podcast RSS / Atom XML 解析示例（演示 `ctx.http.request`、`ctx.html.stripTags` / `unescape`、`episodes` 与 `resolve`）。
