# 第三方组件与开源许可声明

本项目 **MeloKeep** 主体代码（除另有单独许可声明的子目录或文件外）采用 [MIT License](LICENSE) 开源发布。

MeloKeep 在构建与运行过程中使用了下列第三方开源组件与外部工具，各组件保留其各自的版权归属与许可协议条款：

## 1. Go 后端核心与依赖（详见 `go/go.mod`）

- **Go 标准库与工具链**（BSD-3-Clause）
- **`modernc.org/sqlite`**（BSD-3-Clause）— 纯 Go 实现的 SQLite 嵌入式数据库驱动（含 `modernc.org/libc`、`modernc.org/mathutil`、`modernc.org/memory` 等关联组件）
- **`github.com/dop251/goja`**（MIT）— 纯 Go 实现的 ECMAScript 运行时，用于隔离执行音源扩展插件沙箱（含 `github.com/dlclark/regexp2`、`github.com/go-sourcemap/sourcemap`）
- **`github.com/google/uuid`**（BSD-3-Clause）— UUID 生成与解析
- **`golang.org/x/sys`、`golang.org/x/text`**（BSD-3-Clause）— 系统调用封装与文本编码处理
- **`github.com/dustin/go-humanize`、`github.com/mattn/go-isatty`、`github.com/ncruces/go-strftime`、`github.com/remyoudompheng/bigfft`**（MIT / BSD 类开源协议）

## 2. Python 标签辅助组件（仅按需调用 `helpers/mutagen/tagger.py`）

- **`mutagen`**（GPL-2.0-or-later）— 多格式音频元数据标签、歌词与封面读写
- **`requests` & `PySocks`（`requests[socks]`）**（Apache-2.0 / BSD-3-Clause）— 运行时工具网络依赖
- **`python-dotenv`**（BSD-3-Clause）— 容器入口 `.env` 配置读取

## 3. 外部与容器内置工具

- **`FFmpeg` / `ffprobe`**（LGPL / GPL，具体以宿主或容器发行版构建为准）— 音频流完整性解码检查、格式转码与 CUE 分轨
- **`Chromaprint` / `fpcalc`**（LGPL-2.1 / MIT）— 本地音频声学指纹计算（配合 AcoustID 识别）
- **`telegram-bot-api`**（Boost Software License 1.0，镜像内随附 `/licenses/LICENSE-telegram-bot-api`）— 可选内置官方 Telegram Local Bot API Server，用于 50 MB 以上大文件发送

## 4. Web 前端依赖（详见 `frontend/package.json`）

- **`React` & `React DOM`**（MIT）— 前端用户界面框架
- **`Vite`**（MIT）— 前端开发服务器与构建工具
- **`TypeScript`**（Apache-2.0）— 静态类型系统
- **`lucide-react`**（ISC）— 图标库

## 5. 原生移动端组件（位于 `mobile/`）

- **`Navic`**（GPL-3.0，完整协议见 [`mobile/LICENSE`](mobile/LICENSE)）— Android 原生音乐播放界面与 Media3 播放管线基础
- **`subsonic-kotlin`**（MIT）— Kotlin Multiplatform Subsonic / OpenSubsonic 客户端
- **Kotlin Multiplatform、Jetpack Compose / Material 3、AndroidX Media3、Jellyfin Media3 FFmpeg Decoder、Ktor、Coil、Koin 等**（Apache-2.0 / GPL / LGPL 各自对应协议，详见 `mobile/gradle/libs.versions.toml`）

---

使用或二次分发本项目的编译产物与容器镜像时，请自行核对并遵守上述各第三方组件适用的开源许可证条款。
