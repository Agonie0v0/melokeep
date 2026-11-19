<div align="center">

<img alt="Navic 应用图标" width="112" height="112" src="../fastlane/metadata/android/en-US/images/icon.png">

# Navic · Android 增强版

状态栏歌词 · 曲目多选 · 歌单批量管理 · 播放队列搜索

[下载 APK](https://github.com/Agonie0v0/Navic/releases) · [构建状态](https://github.com/Agonie0v0/Navic/actions/workflows/release-android.yml) · [上游项目](https://github.com/ssalggnikool/Navic)

</div>

基于 [Navic](https://github.com/ssalggnikool/Navic) 的个人 Android 定制版本。在原有音乐播放体验上，加入状态栏歌词、曲目批量操作和队列搜索，并补全简体中文文案。

本仓库专注于 **Android**，不提供 iOS 构建。它是非官方衍生版本，保留上游项目的署名与许可证。

## 下载与使用

1. 打开 [Releases](https://github.com/Agonie0v0/Navic/releases)，选择需要的版本，下载 `Navic-statusbar.apk`。
2. 安装后，连接自己的 Subsonic / OpenSubsonic 兼容音乐服务。
3. 同一签名的本仓库版本可以覆盖更新。

应用是音乐客户端，不提供音乐资源或服务器。APK 文件名保留 `statusbar`，其中也包含本仓库的多选、歌单管理和队列搜索功能；具体以对应版本的代码为准。

**从官方版切换：** 本仓库使用独立签名，与原作者 APK 的签名不同，不能直接覆盖安装。需要卸载原版时，请先备份数据。

本地 Debug 构建名为 **Navic (Dev)**，使用独立包名，可与正式版并存。

## 本版本增加了什么

| 功能 | 使用方式 |
| --- | --- |
| 状态栏歌词 | 在支持相应协议的系统状态栏显示当前歌词，并随播放状态与进度同步 |
| 曲目多选 | 在曲目页点击勾选列表图标，选择多首歌曲，支持全选、取消全选和退出选择 |
| 批量加入播放队列 | 将选中的曲目按页面顺序追加到当前队列；遇到重复歌曲时沿用重复提示设置 |
| 批量加入歌单 | 添加到已有歌单，或直接创建包含所选曲目的新歌单 |
| 播放队列搜索 | 按歌曲名、歌手或专辑查找当前队列中的歌曲 |
| 搜索结果下一首播放 | 点击结果右侧的下一首图标，在当前歌曲之后插入一份，不打断正在播放的歌曲 |
| 简体中文 | 补全设置、对话框和新增功能的简体中文文案 |

### 曲目多选与歌单管理

曲目页顶部的勾选列表图标用于进入多选模式。进入后，点击歌曲或复选框切换选中状态，顶部显示已选数量。

- **全选图标：** 选中当前列表中的全部曲目，再次点击取消全选。
- **队列图标：** 将所选曲目追加到当前播放队列。
- **歌单添加图标：** 选择已有歌单，或点击「新建」创建包含这些曲目的歌单。
- **关闭图标或系统返回：** 退出多选模式。

图标支持长按提示。普通模式保留原有的点击播放、长按菜单和滑动快捷操作；多选期间停用滑动快捷操作，避免误触。取消歌单弹窗或提交失败时，保留当前曲目选择。

### 搜索当前播放队列

在播放界面打开队列，使用顶部搜索框筛选歌曲。搜索只筛选当前队列，不会搜索整个音乐库，也不会改变队列顺序。

- **点击歌曲：** 立即播放该结果对应的队列曲目。
- **点击右侧下一首图标：** 将该歌曲新增到当前歌曲之后，保留它在队列中的原有位置。
- **清空搜索：** 恢复完整队列和拖动排序。

搜索时仍可移除结果对应的曲目。队列中同一首歌曲出现多次时，各个位置分别处理；筛选期间隐藏拖动排序入口，避免误改顺序。

### 状态栏歌词

状态栏歌词依赖系统 ROM 支持魅族 / Flyme 状态栏歌词协议。普通 ROM 可能无法显示，不代表应用内歌词不可用。

时间校准入口：**设置 → 正在播放 → 歌词 → 状态栏歌词时间**。

- 调整范围为 `−2000 ms` 至 `+2000 ms`，步进 `100 ms`。
- 负数让歌词延后显示，正数让歌词提前显示。

## 自动构建与发布

[Build and release Android](https://github.com/Agonie0v0/Navic/actions/workflows/release-android.yml) 在每次推送到 `master` 后，自动构建本次提交的 Android 正式版。上游自动同步已取消，不再定时查询或合并上游代码。

工作流会：

1. 检出本次提交，初始化构建所需的 Git 子模块。
2. 检查简体中文资源覆盖情况，构建 Android Release APK，并检查队列搜索与曲目选择顺序。
3. 使用仓库现有证书签名，并将 `Navic-statusbar.apk` 发布到 [Releases](https://github.com/Agonie0v0/Navic/releases)。

其他分支和 Pull Request 由 `Build` 工作流进行 Debug 构建与检查，不发布正式版。

需要手动构建时，在上述工作流页面选择 **Run workflow → master** 后运行。

发布标签为 `android-<运行序号>-<提交短哈希>`。重新运行同一次构建时，会更新该 Release 的 APK。沿用原有签名，可以覆盖安装本仓库此前发布的正式版。

自动发布依赖 Actions 启用及签名密钥配置有效。出现中文资源缺失、依赖下载、编译或回归检查失败时，任务会停止，需要修复后重新运行。手机上的应用仍需下载安装新版。

## 本地构建与检查

准备 **JDK 21** 和项目要求的 **Android SDK**。beta01 使用 `app` 模块，并通过 Git 子模块构建 Media3、FFmpeg 和 Subsonic 客户端。Gradle 会按项目配置安装所需的 NDK 与 CMake。

在 **Linux、macOS 或 WSL** 中构建；新版 FFmpeg 构建脚本不支持直接使用 Windows PowerShell：

```bash
git submodule update --init --recursive --jobs 4
./gradlew :app:assembleDebug
```

测试 APK 输出到 `app/build/outputs/apk/debug/Navic.apk`。无需本地构建时，可使用本仓库的 GitHub Actions。

构建后可运行队列搜索回归检查与中文覆盖检查（需要 Python 3.11+）：

```bash
python checks/check_queue_search.py
python .github/scripts/check-zh-coverage.py
```

更多实机验收项目见 [Android 功能检查清单](../checks/README.md)。命令行检查不替代实际设备上的播放、交互和歌单服务端验证。

## 上游与许可

- 上游项目：[ssalggnikool/Navic](https://github.com/ssalggnikool/Navic)
- 本仓库：[Agonie0v0/Navic](https://github.com/Agonie0v0/Navic)
- 许可证：[GNU GPL v3](../LICENSE)

感谢 Navic 原作者及所有贡献者。本仓库的改动建立在他们的工作之上。
