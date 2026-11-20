# MeloKeep 纯原生移动端开发与构建指南（Navic + Kotlin Multiplatform）

MeloKeep 移动端采用 **纯原生 Kotlin Multiplatform + Jetpack Compose Material 3** 架构，基于上游开源客户端 [`Agonie0v0/Navic`](https://github.com/Agonie0v0/Navic) 与 [`Nightdavisao/subsonic-kotlin`](https://github.com/Nightdavisao/subsonic-kotlin) 深度整合而成，**彻底移除 Capacitor / WebView 套壳**，直接复用与桌面 Web 端对等的 Go 后端全部能力与原厂级本地音频播放管线。

---

## 1. 工程结构总览

| 目录 / 文件 | 职责说明 |
| :--- | :--- |
| [`mobile/shared/`](../mobile/shared/build.gradle.kts) | KMP 共享业务模块（`Android` + `iosArm64` + `iosSimulatorArm64`）：`MeloSettingsStore` 持久化（含 `MeloServerConfig.displayName` 自定义服务名称）、`MeloKeepApiClient`（SSE 实时搜索流、自动 `media_token` 媒体签名刷新、下载/刮削/AI/配置 API）、`MeloAppUpdater`（跨平台 GitHub Release 版本检查与语义版本比较）与四大跨平台控制器 |
| [`mobile/app/`](../mobile/app/build.gradle.kts) | Android 纯原生客户端（`io.github.agonie0v0.melokeep`，Debug 版 `.debug` / `MeloKeep (Dev)`）：4 Tab 架构（`曲库` / `探索` / `工坊` / `我的`），Navic 原生双源曲库（全页下拉刷新、歌曲行左滑下一首 / 右滑队尾、副标题极简已离线微标）、Media3 `1.9.0` + Jellyfin FFmpeg 软解、ReplayGain 增益、均衡器（内聚于「音质与增益」）、Flyme 状态栏歌词、应用内 APK 下载覆盖安装，以及挂载在 [`melokeep/`](../mobile/app/src/main/kotlin/paige/navic/melokeep) 下的原生扩展界面与 merged 5 分组「我的」工作台 |
| [`mobile/iosApp/compose/`](../mobile/iosApp/compose/build.gradle.kts) | iOS Compose Multiplatform 模块（`:iosCompose`），编译生成静态 `ComposeApp.framework`，提供对等的 4 Tab 交互（双源下拉刷新、滑动入队、工坊直连 `startScan`、5 分组「我的」工作台与更新日志 / SideStore & AltStore 引导） |
| [`mobile/iosApp/iosXcode/`](../mobile/iosApp/iosXcode/MeloKeep.xcodeproj/project.pbxproj) | iOS 原生 Xcode 宿主工程（Scheme: `MeloKeep`，含 `AVAudioSession(.playback)`、`AVQueuePlayer`、`MPNowPlayingInfoCenter` / `MPRemoteCommandCenter`、文档目录离线存储） |
| [`mobile/vendor/subsonic-kotlin/`](../mobile/vendor/subsonic-kotlin) | 本地 composite build 子工程，为 Android 与 iOS 提供 Subsonic/Navidrome API 客户端（含独立曲库、下载与工坊直连 `startScan` 扫库） |
| [`mobile-dev.ps1`](../mobile-dev.ps1) | Windows 联调脚本：联动 Go 后端热重载，支持 `-InstallApk` 与 `-WatchAndroid` 自动增量编译与重启 App |
| [`.github/workflows/build-mobile.yml`](../.github/workflows/build-mobile.yml) | GitHub Actions 双端可复用打包工作流：支持按需编译 Release APK（自动签名并校验）与 `MeloKeep-<version>-ios-unsigned.ipa` |

---

## 2. 便携 Android 编译工具链准备

即使本机未全局安装 Android Studio、JDK 或 Android SDK，也可通过仓库内置的一键脚本初始化隔离工具链至 `.dev-bin/android-toolchain/`（已加入 `.gitignore`）：

```powershell
# 初始化或修复便携 OpenJDK 21 + Android SDK (platform-tools, android-37.2, build-tools 37.0.0)
.\mobile\scripts\setup-android-toolchain.ps1
```

若处于公司代理或需要代理访问 Maven / JitPack 仓库的环境下（如环境变量设置了 `HTTP_PROXY` / `HTTPS_PROXY`），Gradle 会通过以下方式透传代理参数（或由 `mobile-dev.ps1` 自动注入）：

```powershell
$env:JAVA_HOME = "$PWD\.dev-bin\android-toolchain\jdk-21"
$env:ANDROID_HOME = "$PWD\.dev-bin\android-toolchain\android-sdk"
cd mobile
.\gradlew.bat :shared:testAndroidHostTest :app:assembleDebug --no-daemon --console=plain `
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808 `
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808
```

编译成功后产物位于 `mobile\app\build\outputs\apk\debug\MeloKeep.apk`（由构建脚本统一归档为 `melokeep_outputs\MeloKeep-android-debug.apk`）。

---

## 3. 日常联调与自动热重启（Windows）

### 启动联调 + 首次安装运行

```powershell
# 启动 Go 后端监听（0.0.0.0:8765）+ 编译并安装 Debug APK + 自动重启 App
.\mobile-dev.ps1 -InstallApk
```

### 源码热监听自动重编重启（`-WatchAndroid`）

```powershell
# 开启 Kotlin/资源文件监听：改完 .kt / .kts / .xml 后自动增量编译、adb install -r 并强制重启
.\mobile-dev.ps1 -WatchAndroid
```

### USB 数据线本地调试（无需局域网 IP）

```powershell
# 使用 adb reverse 将手机 127.0.0.1:8765 转发到电脑 Go 后端
.\mobile-dev.ps1 -WatchAndroid -UseAdbReverse
```

连接真机后，在 App 内进入 **「我的 → 服务与连接」**（或首次启动引导界面）填入控制台打印的后端地址（局域网 IP 或 `http://127.0.0.1:8765`）并点击 **「测试连接并保存登录」**；也可在此处独立配置自建 Navidrome，支持自定义服务显示名称并默认隐藏敏感服务器地址。App 内播放、下载与工坊扫库会优先直连 App 自身配置的 Navidrome，无需在 Web 端重复配置。

---

## 4. GitHub Release 双端自动发版与 Android 正式签名配置

### 自动随 Release 发布双端安装包

主发版流水线 [`.github/workflows/docker-publish.yml`](../.github/workflows/docker-publish.yml) 在每次向 `main` 推送非纯文档变更并发布新版本（如 `v1.0.13`）时，会自动比对上一个版本 Tag 以来 `mobile/**` 或 [`.github/workflows/build-mobile.yml`](../.github/workflows/build-mobile.yml) 是否有变更：

- **移动端有变更（或手动触发时选择 `build_mobile=true`）**：并行执行 Android Release（仅真机 `arm64-v8a` / `armeabi-v7a`，R8 优化瘦身 + `zipalign` + `apksigner` 签名校验）与 iOS Release 构建，并将版本号自动注入安装包，随 Release 一同发布以下附件：
  - **`MeloKeep-<version>-android.apk`**：Android 正式安装包（应用内「我的 → 更新与关于」支持检查新版本、直接下载并通过 `REQUEST_INSTALL_PACKAGES` / `FileProvider` 拉起系统覆盖安装）。
  - **`MeloKeep-<version>-ios-unsigned.ipa`**：iOS 未签名安装包（适配 SideStore / AltStore / TrollStore 自签；应用内「我的 → 更新与关于」支持展示 Release 更新日志并提供 SideStore / AltStore 一键拉起入口）。
- **仅后端或 Web 变更**：自动跳过双端编译，只发布 Docker 镜像与 Release 更新日志，节省构建时间；仅 Markdown / `docs/**` 文档变更会跳过整条发版流水线。

### Android 固定签名 Secrets 配置（实现无缝覆盖升级）

与 `Agonie0v0/Navic` 保持一致的 4 个仓库 Secrets 名称，配置在 GitHub 仓库的 **Settings -> Secrets and variables -> Actions -> Repository secrets**：

| Secret 名称 | 说明 |
| :--- | :--- |
| `KEYSTORE` | 签名证书文件（`.jks` 或 `.keystore`）的 Base64 编码单行文本 |
| `KEY_ALIAS` | 证书密钥别名（Alias） |
| `KEY_PASSWORD` | 私钥密码（Key Password） |
| `KEYSTORE_PASSWORD` | 证书库密码（Store Password） |

> 未配置完整上述 4 个 Secret 时，流水线会自动使用临时回退证书签名兜底（可正常安装体验，但下次更新因签名不同需卸载重装）。配置完成后，可直接在 GitHub Actions 手动运行 **「构建移动端双端包」** 并勾选 **`publish_to_latest_release`**，即可一键把正式签名包覆盖上传到当前最新 Release。

#### 在 Windows PowerShell 7 生成/导出 Base64（二选一）：

```powershell
# 方式 A：已有 Navic 使用的 release.keystore / .jks 文件，导出单行 Base64 到剪贴板
[Convert]::ToBase64String([IO.File]::ReadAllBytes('.\release.keystore')) | Set-Clipboard

# 方式 B：用仓库便携 JDK 的 keytool 新建一套专属证书并导出 Base64（不要提交 .keystore 到 Git）
.\.dev-bin\android-toolchain\jdk-21\bin\keytool.exe -genkeypair -v `
  -keystore .\melokeep-release.keystore -storetype PKCS12 -alias melokeep `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -dname "CN=MeloKeep,O=Agonie0v0,C=CN"
[Convert]::ToBase64String([IO.File]::ReadAllBytes('.\melokeep-release.keystore')) | Set-Clipboard
```

### iPhone / iPad 通过 SideStore / AltStore 免账号自签安装

1. 前往项目 GitHub Releases 页面下载最新版 `MeloKeep-<version>-ios-unsigned.ipa`（或于 macOS 本地执行 `bash mobile/iosApp/build-ios-ipa.sh` 后从 `melokeep_outputs/` 获取）。
2. 在已部署 **SideStore** 或 **AltStore** 的 iOS 设备上导入该 `.ipa`，使用个人免费 Apple ID 完成本地重签安装。
3. 前往 iOS **「设置 -> 通用 -> VPN 与设备管理」** 信任对应个人开发者证书。
4. 首次打开 App 后，进入底部 **「我的 → 服务与连接」** 配置 MeloKeep 服务地址与 Navidrome 服务器即可后台播放、锁屏控播与 AI 识图导歌单。

---

## 5. 开源许可与致谢

- Android 原生 UI 与播放管线基于 [`Agonie0v0/Navic`](https://github.com/Agonie0v0/Navic) 修改整合，遵循 **GPL-3.0** 协议（完整许可证见 [`mobile/LICENSE`](../mobile/LICENSE)）。
- Subsonic/Navidrome Kotlin Multiplatform 客户端基于 [`Nightdavisao/subsonic-kotlin`](https://github.com/Nightdavisao/subsonic-kotlin)（**MIT** 协议）。
