# ZCode Mobile App

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-API%2024%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![WebView](https://img.shields.io/badge/Chromium-WebView-4285F4?logo=googlechrome&logoColor=white)](https://developer.android.com/develop/ui/views/layout/webapps/webview)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**简体中文** | [English](README.en.md)

ZCode Mobile App 是 ZCode 远程控制的 Android 客户端。

ZCode Mobile App 对官方远程控制页面做了原生封装，用 App 容器替代手机浏览器来承载页面，并针对手机屏幕、触摸操作和系统行为进行适配，让远程控制在手机上更稳定、更好用。

> 注：本应用不替换 ZCode 官方的远端执行能力，只负责连接管理、原生容器与移动端交互优化。

<table>
  <tr>
    <td><img src="https://github.com/my-dlq/zcode-mobile-app/blob/main/images/zmobile-01.png" alt="Screenshot 1"></td>
    <td><img src="https://github.com/my-dlq/zcode-mobile-app/blob/main/images/zmobile-02.png" alt="Screenshot 2"></td>
    <td><img src="https://github.com/my-dlq/zcode-mobile-app/blob/main/images/zmobile-03.png" alt="Screenshot 3"></td>
    <td><img src="https://github.com/my-dlq/zcode-mobile-app/blob/main/images/zmobile-04.png" alt="Screenshot 4"></td>
  </tr>
</table>

## 功能特性

- **安全功能:** 增加指纹验证、图案验证功能。
- **界面主题:** 内置浅色/深色两套主题，可在设置中手动切换。
- **事件通知:** 支持任务成功/失败/审批/提问四类事件，每项可以单独开启。
- **会话记忆:** 按设备记录上次所在的任务会话，切回或应用被系统回收后自动恢复到原设备与原会话。
- **应用内更新:** 启动时静默检查 GitHub Release，支持下载 APK 并拉起系统安装器、忽略指定版本。
- **连接管理:** 扫码、相册识别、剪贴板检测、深链接四种入口快速添加远程设备，多连接统一管理，支持排序。
- **移动交互:** 沉浸式全屏、窄屏适配、误触抑制、设置页面UI优化、返回键分级处理、软键盘弹出自动抬高页面，提供更好的使用体验。
- **后台保活:** 远程会话期间以前台服务 + 息屏 WakeLock 保持连接，避免页面被系统回收，并提供电池优化引导。

## 技术栈

| 类别 | 技术 |
| --- | --- |
| 开发语言 | Kotlin 1.9.24 |
| UI | Android View、XML、ViewBinding、Material Components |
| Web 容器 | AndroidX WebKit、系统 Chromium WebView |
| 扫码 | CameraX 1.3.3、ZXing Core 3.5.3 |
| 数据存储 | SharedPreferences、Gson |
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

## 构建与测试

在项目根目录执行：

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

Debug APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 开发约定

- 新功能按 `ui/<feature>` 组织，页面私有组件放在对应 feature 目录中。
- 数据模型放在 `data/model`，持久化逻辑放在 `data/repository`。
- WebView 相关逻辑集中在 `ui/remote/web`。
- 新增 Activity 需要注册 Manifest，并通过目标 Activity 的 `start(...)` 方法启动。
- 用户可见文案和代码注释使用中文，沿用现有项目风格。
- 修改 WebView 注入规则前，应先确认远端 DOM 结构，再通过模拟器/CDP 验证真实页面。
- 每组相关改动完成并验证后立即创建中文 Git 提交。

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
- 应用内展示的任务进度、图表、终端输出等页面内容均由 ZCode 官方远端服务生成并托管，远程地址（如 `https://zcode.z.ai/remote`）的所有权与运营权均属于 ZCode 官方，本项目不存储、不中转任何远端数据。
- 本项目通过注入 CSS/JS 对官方页面做移动端显示适配，可能随官方改版失效；请勿将由此产生的显示或使用问题归咎于官方。
- 使用本项目连接远程服务时，请遵守 ZCode 官方的服务条款与许可协议，风险由使用者自行承担。

## 许可协议

本项目基于 [MIT License](LICENSE) 发布。
