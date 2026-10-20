# ZCode Mobile App

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-API%2024%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![WebView](https://img.shields.io/badge/Chromium-WebView-4285F4?logo=googlechrome&logoColor=white)](https://developer.android.com/develop/ui/views/layout/webapps/webview)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**简体中文** | [English](README.en.md)

ZCode Mobile App 是用于连接 ZCode 远程控制页面的第三方 Android 客户端，支持 Android 7.0 及以上版本。

ZCode Mobile App 对官方远程控制页面做了原生封装，用 App 容器替代手机浏览器来承载页面，并针对手机屏幕、触摸操作和系统行为进行适配，让远程控制在手机上更稳定、更好用。

> 注：本应用不替换 ZCode 官方的远端执行能力，只负责连接管理、原生容器与移动端交互优化。

## 界面预览

以下为当前版本的真机界面截图，点击图片可查看原图。连接、账号、任务、代码和服务配置已替换为示例内容，便于展示页面样式；示例内容未写入真实工作区。模型名称与能力标记用于展示菜单和设置布局。

<table>
  <tr>
    <td width="33%" align="center"><a href="images/zmobile-connections.png"><img src="images/readme/zmobile-connections.png" alt="连接列表：开发工作站、个人笔记本和测试主机" width="100%"></a><br><b>连接管理</b><br>保存多台电脑，查看连接状态与排序。</td>
    <td width="33%" align="center"><a href="images/zmobile-workspaces.png"><img src="images/readme/zmobile-workspaces.png" alt="连接 ZCode 后的项目与任务列表" width="100%"></a><br><b>项目与任务</b><br>连接 ZCode 后，按项目浏览和切换任务。</td>
    <td width="33%" align="center"><a href="images/zmobile-conversation.png"><img src="images/readme/zmobile-conversation.png" alt="待办清单搜索功能的示例对话" width="100%"></a><br><b>任务对话</b><br>阅读执行结果，继续提问并查看代码变更。</td>
  </tr>
  <tr>
    <td align="center"><a href="images/zmobile-model-menu.png"><img src="images/readme/zmobile-model-menu.png" alt="对话中的模型选择、能力标记与思考强度菜单" width="100%"></a><br><b>模型选择适配</b><br>在对话中选择模型，查看能力与思考强度。</td>
    <td align="center"><a href="images/zmobile-code-review.png"><img src="images/readme/zmobile-code-review.png" alt="侧边审查面板中的示例文件与代码差异" width="100%"></a><br><b>侧边代码审查</b><br>在侧边面板浏览文件，查看增删行和代码差异。</td>
    <td align="center"><a href="images/zmobile-model-settings.png"><img src="images/readme/zmobile-model-settings.png" alt="模型设置中的供应商、模型列表与能力标记" width="100%"></a><br><b>模型设置适配</b><br>在手机上查看供应商、模型列表与能力配置。</td>
  </tr>
</table>

## 功能特性

- **连接管理：** 支持扫码、手动输入和剪贴板快捷连接；保存多条连接，支持编辑、复制链接、删除及长按拖动排序。
- **会话记忆：** 按连接记录最近所在的任务会话，应用恢复时尝试回到原连接与会话。
- **事件通知：** 支持任务完成、任务失败、等待审批、等待回答四类通知，可分别开启或关闭；点击通知可进入对应任务会话。
- **后台保活：** 可开启前台服务与息屏 WakeLock，降低后台连接中断风险，并提供电池优化设置引导。
- **显示设置：** 支持沉浸式全屏、顶部栏显示和页面整体缩放；内置浅色、深色、石墨灰主题。
- **页面适配：** 优化任务列表、设置页及弹窗的窄屏布局，调整对话输入字号，减少项目和模型选择菜单误唤起键盘的问题。
- **移动交互：** 支持返回键分级处理、软键盘避让，以及误触抑制和长按文本选择。
- **安全功能：** 支持指纹验证和图案验证。
- **界面语言：** 应用界面支持简体中文、English 和跟随系统。
- **活动提醒：** 接入 ZCode 的 token 活动领取提醒，账号有可领取活动时，在任务会话内弹窗提示。
- **应用内更新：** 可在启动时检查 GitHub Release，支持下载 APK、拉起系统安装器和忽略指定版本。

## 快速开始

1. 从 [GitHub Releases](https://github.com/my-dlq/zcode-mobile-app/releases) 下载并安装正式 APK。
2. 在电脑端 ZCode 中开启远程控制，获取远程链接或二维码，并保持电脑端在线。
3. 打开 App，通过菜单中的扫码或添加连接入口保存地址；剪贴板中有可识别的远程链接时，也可使用快捷连接。
4. 点击保存的连接进入远程页面。如显示“等待桌面端确认配对”，请先在电脑端完成相应的连接或配对流程，再选择项目和任务会话。

连接列表可保存多个地址；**当前只监听最近打开的连接**，切换连接后，事件监听目标随之切换。

### 两类设置

| 入口 | 作用 |
| --- | --- |
| App 菜单中的设置 | 管理显示、主题、语言、通知、安全验证、后台保活和应用更新 |
| 远程页面内的设置 | 管理 ZCode 工作区、模型、插件等远端配置，具体内容随官方页面变化 |

## 通知与返回操作

### 四类事件通知

| 类型 | 提醒内容 |
| --- | --- |
| 等待审批 | 任务需要你批准或拒绝操作 |
| 等待回答 | 任务需要你回答问题或补充信息 |
| 任务完成 | 任务或后台执行器报告完成 |
| 任务失败 | 任务报告失败 |

- 在 App 设置的通知管理中开启总开关，并按需选择事件类型；同时允许系统通知权限。
- 正在前台查看某个会话时，该会话的事件不再重复弹出系统通知，其他会话的事件仍按设置提醒。
- 点击通知会唤起对应连接并定位目标会话；远程地址和目标会话需要仍可访问。
- 需要后台接收通知时，可开启后台保活，并按手机系统的提示调整电池限制。桌面端离线、网络中断或应用被系统强制停止时，监听可能中断。

### 系统返回键与滑动返回

| 当前界面 | 返回操作 |
| --- | --- |
| 软键盘、菜单、弹窗或侧栏已打开 | 优先收起键盘或关闭当前浮层 |
| 任务会话、设置详情等页面 | 按页面层级返回上一级 |
| 远程任务首页 | 在提示后的短时间内再次返回，进入 App 连接列表 |
| App 连接列表 | 再次返回直接回到桌面 |

从连接列表回到桌面会保留后台远程页面；已开启的后台保活继续按设置运行。后台页面仍存活时，重新打开 App 会回到连接列表；进程被系统回收后，则会尝试恢复最近连接与会话。

## 常见问题

**一直显示“等待桌面端确认配对”怎么办？**

确认电脑端 ZCode 在线，当前远程控制入口仍有效，并完成电脑端要求的配对。若链接已失效，重新获取远程链接并更新保存的连接。

**为什么没有收到任务通知？**

先检查 App 通知总开关、对应事件类型和系统通知权限，再确认当前打开的是产生事件的连接。正在前台查看同一会话时不会重复通知；需要后台接收时，再检查保活开关和系统电池限制。

**为什么回到桌面后仍显示后台守护通知？**

返回桌面只退出前台界面，后台保活可继续运行。若不需要后台保活，可在 App 设置中关闭该开关。

**官方页面更新后出现布局或操作异常怎么办？**

先确认安装的是最新可用 APK；若问题仍存在，请通过 Issue 提供手机型号、Android 版本、App 版本和复现步骤。日志与截图中的远程链接、设备标识和 Token 应先移除。

## 本地数据与连接说明

- 应用在手机本地保存连接地址、连接名称、最近会话和用户偏好。
- 为支持通知投递及重复事件去重，本地 SQLite 数据库还会保存任务标识、标题、事件类型和处理状态等记录。
- 远程页面及通信由 WebView 与相应远程服务建立连接，本项目不额外提供远端数据中转服务。页面登录状态与缓存由 WebView 管理。
- 远程链接可能包含连接凭据，请勿将真实链接放入公开文档、Issue 或截图中。

## 技术栈

| 类别 | 技术 |
| --- | --- |
| 开发语言 | Kotlin 1.9.24 |
| UI | Android View、XML、ViewBinding、Material Components |
| Web 容器 | AndroidX WebKit、系统 Chromium WebView |
| 扫码 | CameraX 1.3.3、ZXing Core 3.5.3 |
| 数据存储 | SharedPreferences、Gson、SQLite（通知投递与去重记录） |
| 最低版本 | Android 7.0 / API 24 |
| 编译目标 | compileSdk 34 / targetSdk 34 |
| Java | JDK 17 |
| 构建 | Gradle、Android Gradle Plugin 8.4.1 |

## 环境准备

- Android Studio Hedgehog、Iguana、Jellyfish 或更高版本
- JDK 17
- Android SDK Platform 34
- Android SDK Build Tools
- Android 模拟器或开启 USB 调试的 Android 设备

构建前将 `JAVA_HOME` 指向 JDK 17，并通过 Android Studio 配置本机 Android SDK。

## 构建与测试

在项目根目录执行：

以下命令适用于 macOS、Linux 或 Git Bash。Windows PowerShell / CMD 请将 `./gradlew` 替换为 `.\gradlew.bat`。

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease

# 运行 JVM 单元测试
./gradlew testDebugUnitTest

# 运行 Android 仪器化测试，需要连接设备或模拟器
./gradlew connectedDebugAndroidTest

# 运行静态检查
./gradlew lint
```

APK 输出路径（`<版本>` 取自 `app/build.gradle.kts` 的 `versionName`）：

```text
app/build/outputs/apk/debug/zcode-mobile-app-v<版本>-debug.apk
app/build/outputs/apk/release/zcode-mobile-app-v<版本>.apk
```

Debug 包名为 `ai.zcode.remote.debug`，正式包名为 `ai.zcode.remote`，两者可共存，连接数据和登录状态相互独立。

## 开发约定

- 新功能按 `ui/<feature>` 组织，页面私有组件放在对应 feature 目录中。
- 数据模型放在 `data/model`，持久化逻辑放在 `data/repository`。
- WebView 相关逻辑集中在 `ui/remote/web`。
- 新增 Activity 需要注册 Manifest，并通过目标 Activity 的 `start(...)` 方法启动。
- 用户可见文案和代码注释使用中文，沿用现有项目风格。
- 修改 WebView 注入规则前，应先确认远端 DOM 结构，再通过模拟器/CDP 验证真实页面。
- 相关改动聚合为一个逻辑提交，提交说明包含功能变化与验证结果；自动化协作时，按维护者授权执行提交、推送和合并。

## 贡献指南

欢迎提交 Issue、改进建议和 Pull Request。

### 提交 Issue

请尽量提供以下信息：

- 手机或模拟器型号
- Android 版本
- APP 版本或 APK 构建时间
- 复现步骤
- 截图或录屏
- 相关日志（注意移除远程链接、设备 ID、Token 等敏感信息）

### 提交 Pull Request

1. Fork 本项目并创建独立分支，例如 `feature/xxx` 或 `fix/xxx`。
2. 保持修改范围明确，避免将无关重构混入功能修复。
3. 涉及 Android UI 的改动，请在模拟器或真实设备上验证。
4. 涉及 WebView 注入的改动，请同时验证原生层（adb / 截图）和 CDP 页面层，并在 Pull Request 中说明验证环境与结果。
5. 提供清晰的提交说明和测试结果。

## 免责声明

- 本项目为社区独立开发的第三方开源客户端，与 [ZCode](https://zcode.z.ai) / Z.ai 官方**无任何关联**，未获得官方授权、赞助或背书。
- 「ZCode」「Z.ai」及相关名称、商标、Logo 归其各自权利人所有，本项目仅作指代官方产品之用。
- 应用内展示的任务进度、图表、终端输出等页面内容均由 ZCode 官方远端服务生成并托管，官方远程地址（如 `https://zcode.z.ai/remote`）的所有权与运营权均属于 ZCode 官方。本项目负责客户端连接与显示适配，本地保存的信息见“本地数据与连接说明”。
- 本项目通过注入 CSS/JS 对官方页面做移动端显示适配，可能随官方改版失效；请勿将由此产生的显示或使用问题归咎于官方。
- 使用本项目连接远程服务时，请遵守 ZCode 官方的服务条款与许可协议，风险由使用者自行承担。

## 许可协议

本项目基于 [MIT License](LICENSE) 发布。
