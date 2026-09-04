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
    <td><img src="http://img.mydlq.club/img/zmobile-01.png?x-oss-process=style/shuiyin" alt="Screenshot 1"></td>
    <td><img src="http://img.mydlq.club/img/zmobile-02.png?x-oss-process=style/shuiyin" alt="Screenshot 2"></td>
    <td><img src="http://img.mydlq.club/img/zmobile-03.png?x-oss-process=style/shuiyin" alt="Screenshot 3"></td>
    <td><img src="http://img.mydlq.club/img/zmobile-04.png?x-oss-process=style/shuiyin" alt="Screenshot 4"></td>
  </tr>
</table>

## 项目背景

[ZCode](https://zcode.z.ai) 是 Z.ai 推出的智能体开发环境（ADE），内置 **远程控制** 能力：在桌面端打开「移动端远程控制」弹窗，手机扫码或复制链接，即可查看任务进度并继续向智能体下发指令。

该页面面向手机浏览器设计，直接用浏览器打开时：

- 地址栏与工具栏占用有限的屏幕空间
- 长按选择、系统放大镜等行为干扰操作
- 标签页可能被系统回收，导致会话重载或重新连接
- 多个远程地址缺少统一管理入口
- 移动视口、字体缩放和触摸事件引发布局与交互异常
- 全屏、刷新等操作不够直接

本项目用 Android 原生容器替代手机浏览器承载该页面，改动集中在以下三个层面。

### 主要改造点

**连接管理** — 统一管理多个远程连接。支持扫码、相册识别、剪贴板检测和深链接四种入口，保存时按归一化后的 URL 判重，避免同一台设备重复保存。

**原生容器** — 用独立 Activity 替代浏览器标签页：

- 沉浸式全屏；软键盘弹出时自动抬高页面，避免遮挡输入框
- 返回键分级处理：先关面板弹层，再回退页面，最后才退出
- 渲染进程被回收时提示重试，而不是直接崩溃
- 悬浮控制球可拖动，贴近边缘静止后自动半隐藏

**页面适配** — 注入 CSS/JS 修正远端页面在手机上的表现，这是本项目的主体工作：

- 伪装桌面环境，避免窄视口下退化为功能受限的精简页
- 抑制长按误选与系统放大镜，同时保留输入框、终端、消息的选择与粘贴
- 修复触摸异常：拖拽传感器误判导致开关点不动、合成点击叠加导致状态回弹
- 窄屏下远端不显示设置入口，改为临时切桌面视口拉起后再切回
- 补充二级详情页缺失的返回按钮，并压缩侧边栏、表单、图表的窄屏布局

> 页面适配依赖远端页面的 DOM 结构，远端改版时这部分可能需要同步调整。

## 功能特性

### 远程连接

- 扫描桌面端 ZCode 远程二维码
- 支持从系统相册识别二维码
- 解析远程 URL 中的设备名称、`mid` 和 `sid`
- 通过剪贴板检测快速连接
- 支持 `https://zcode.z.ai/remote` 深链接拉起
- 使用本地存储保存多个远程设备
- 支持编辑、删除、设为默认和重新连接

### 移动端交互

- 沉浸式全屏和普通窗口模式切换
- 悬浮控制球可拖动到任意位置
- 悬浮球贴近屏幕边缘静止 5 秒后半隐藏，点击露出部分恢复
- 快捷面板提供远程设置、刷新、全屏、复制 URL、清除缓存和退出列表
- 返回键采用二次确认，降低误触退出概率

### WebView 适配

- 任务会话输入框支持长按粘贴
- 用户提问和模型输出支持长按选中、复制
- 终端和编辑器区域保留正常输入与选择行为
- 优化模型、供应商、思考等级和权限控制列表在移动视口下的显示
- 修复设置中心侧边栏、供应商编辑区和 Token 活动点阵的移动端布局问题
- 通过 CSS/JavaScript 注入处理特定的触摸和页面布局问题

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

## 模拟器调试

本项目针对 WebView 应用提供两条调试通道：

- Android 原生层：`adb`、`uiautomator`、截图和 `logcat`
- WebView 页面层：Chrome DevTools Protocol（CDP）

Debug 构建会开启 WebView 远程调试。连接模拟器后，可以通过以下命令映射 WebView 调试端口：

```bash
PID=$(adb -s <serial> shell pidof ai.zcode.remote.debug)
adb -s <serial> forward tcp:9222 localabstract:webview_devtools_remote_$PID
curl http://127.0.0.1:9222/json
```

上一条 `curl` 会列出可调试的页面。随后可用任意支持 CDP 的工具（如 Chrome DevTools、chrome-devtools-mcp）连接 `http://127.0.0.1:9222`，查看页面结构、执行脚本或截图。

> 调试时注意：debug 构建的包名带 `.debug` 后缀（`ai.zcode.remote.debug`），`pidof` / `am` 等命令都要用全名；若同时运行多台模拟器，所有 adb 命令都需要 `-s <serial>` 指定设备。

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

## 许可协议

本项目基于 [MIT License](LICENSE) 发布。
