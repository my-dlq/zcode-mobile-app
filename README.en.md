# ZCode Mobile App

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-API%2024%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![WebView](https://img.shields.io/badge/Chromium-WebView-4285F4?logo=googlechrome&logoColor=white)](https://developer.android.com/develop/ui/views/layout/webapps/webview)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[简体中文](README.md) | **English**

ZCode Mobile App is an Android client for ZCode Remote Control.

ZCode Mobile App wraps the official remote control page in a native shell: it uses an app container instead of the mobile browser to host the page, and adapts it for phone screens, touch interaction, and system behavior, making remote control more stable and usable on mobile.

> Note: This app does not replace ZCode's remote execution capabilities. It focuses on connection management, the native container, and mobile interaction optimization.

<table>
  <tr>
    <td><img src="http://img.mydlq.club/img/zmobile-01.png?x-oss-process=style/shuiyin" alt="Screenshot 1"></td>
    <td><img src="http://img.mydlq.club/img/zmobile-02.png?x-oss-process=style/shuiyin" alt="Screenshot 2"></td>
    <td><img src="http://img.mydlq.club/img/zmobile-03.png?x-oss-process=style/shuiyin" alt="Screenshot 3"></td>
    <td><img src="http://img.mydlq.club/img/zmobile-04.png?x-oss-process=style/shuiyin" alt="Screenshot 4"></td>
  </tr>
</table>

## Background

[ZCode](https://zcode.z.ai) is an Agentic Development Environment (ADE) from Z.ai with a built-in **Remote Control** feature: open the "Mobile Remote Control" dialog on the desktop client, scan the QR code or copy the link with your phone, and you can track task progress and keep sending instructions to the agent.

That page is designed for mobile browsers. When opened directly in a browser:

- The address and tool bars consume limited screen space
- Long-press selection and the system magnifier interfere with interaction
- The tab may be reclaimed by the system, causing the session to reload or reconnect
- Multiple remote addresses lack a unified management entry point
- The mobile viewport, font scaling, and touch events cause layout and interaction issues
- Full screen, refresh, and similar actions are not directly accessible

This project replaces the mobile browser with an Android native container to host that page. The changes fall into three areas.

### What Was Changed

**Connection management** — Manage multiple remote connections in one place. Supports four entry points: QR scan, gallery recognition, clipboard detection, and deep links. URLs are normalized before deduplication so the same device is never saved twice.

**Native container** — A dedicated Activity replaces the browser tab:

- Immersive full screen; the page is raised when the soft keyboard appears so inputs stay visible
- Back key is handled in stages: close panels and dialogs first, then navigate back, exit last
- When the render process is reclaimed, a retry prompt is shown instead of crashing

**Native settings & security** — Client-side capabilities outside the WebView container:

- App settings: immersive full-screen toggle, light / dark theme, and automatic update check on startup
- App lock: pattern and fingerprint (biometric) unlock; consecutive verification failures trigger a shared lockout countdown
- In-app updates: detect new GitHub Releases versions, download the APK in the background, and upgrade via the system installer
- About page: view version, manually check for updates, and open the GitHub project home

**Page adaptation** — Injecting CSS/JS to fix how the remote page behaves on phones. This is the bulk of the work:

- Spoof the desktop environment to avoid degrading to a limited feature set on narrow viewports
- Suppress accidental long-press selection and the system magnifier, while keeping selection and paste working in inputs, terminals, and messages
- Fix touch issues: drag sensors misjudging taps so switches won't toggle, and synthetic clicks stacking so state reverts
- On narrow screens the remote page hides the settings entry; a desktop viewport is temporarily injected to open it, then switched back
- Add the missing back button on secondary detail pages, and compact sidebars, forms, and charts for narrow screens

> Page adaptation depends on the remote page's DOM structure and may need updating when the remote side changes.

## Features

### Remote Connection

- Scan the ZCode remote QR code from the desktop client
- Recognize QR codes from the system gallery
- Parse device name, `mid`, and `sid` from the remote URL
- Connect quickly via clipboard detection
- Launch via the `https://zcode.z.ai/remote` deep link
- Store multiple remote devices locally
- Edit, delete, set as default, and reconnect

### Native Settings & Security

- Switch between immersive full screen and the native window mode
- Switch between light and dark themes
- App lock: unlock the app with a pattern or fingerprint; too many failed attempts trigger a shared lockout
- Automatic update check on startup (can be disabled); the About page supports manual checks
- Detect new GitHub Releases versions and download with one tap to launch the system installer
- Back key is handled in stages to reduce accidental exits

### WebView Adaptation

- Long-press paste works in the task session input
- User prompts and model output support long-press selection and copy
- Terminal and editor areas keep normal input and selection behavior
- Improved display of model, provider, thinking level, and permission lists on mobile viewports
- Fixed mobile layout issues in the settings sidebar, provider editing area, and Token activity heatmap
- Targeted touch and layout issues are handled via CSS/JavaScript injection

## Tech Stack

| Category | Technology |
| --- | --- |
| Language | Kotlin 1.9.24 |
| UI | Android View, XML, ViewBinding, Material Components |
| Web Container | AndroidX WebKit, system Chromium WebView |
| QR Scanning | CameraX 1.3.3, ZXing Core 3.5.3 |
| Storage | SharedPreferences, Gson |
| Minimum Version | Android 7.0 / API 24 |
| Compile Target | compileSdk 34 / targetSdk 34 |
| Java | JDK 17 |
| Build | Gradle, Android Gradle Plugin 8.4.1 |

## Prerequisites

- Android Studio Hedgehog, Iguana, Jellyfish, or newer
- JDK 17
- Android SDK Platform 34
- Android SDK Build Tools
- An Android emulator or an Android device with USB debugging enabled

> Before building from the command line, make sure the shell uses JDK 17. Set `JAVA_HOME` to your own JDK 17 path (for example `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` on macOS). Run `./gradlew -v` or `java -version` to verify.

## Build and Test

Run from the project root:

```bash
# Build the Debug APK
./gradlew assembleDebug

# Build the Release APK
./gradlew assembleRelease

# Run JVM unit tests
./gradlew testDebugUnitTest

# Run Android instrumented tests, requires a connected device or emulator
./gradlew connectedDebugAndroidTest

# Run static analysis
./gradlew lint
```

> **Build repositories:** Dependency resolution is centralized in `settings.gradle.kts` (`RepositoriesMode.FAIL_ON_PROJECT_REPOS`), so module-level `build.gradle.kts` files must not declare their own `repositories`. It uses Aliyun Maven mirrors (`maven.aliyun.com/repository/google`, `maven.aliyun.com/repository/public`, `maven.aliyun.com/repository/gradle-plugin`) together with Google, Maven Central, and `jitpack.io`, letting the project build in restricted network environments where the default Google/Maven Central endpoints are slow or blocked.

Debug APK output path:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Emulator Debugging

This project provides two debugging channels for WebView-based apps:

- Android native layer: `adb`, `uiautomator`, screenshots, and `logcat`
- WebView page layer: Chrome DevTools Protocol (CDP)

Debug builds enable WebView remote debugging. After connecting an emulator, map the WebView debugging port with:

```bash
PID=$(adb -s <serial> shell pidof ai.zcode.remote.debug)
adb -s <serial> forward tcp:9222 localabstract:webview_devtools_remote_$PID
curl http://127.0.0.1:9222/json
```

The `curl` command lists debuggable pages. You can then connect any CDP-capable tool (Chrome DevTools, chrome-devtools-mcp, etc.) to `http://127.0.0.1:9222` to inspect the DOM, run scripts, or take screenshots.

> Note when debugging: the debug build's package name has a `.debug` suffix (`ai.zcode.remote.debug`), so use the full name in `pidof` / `am` commands. If multiple emulators are running, all adb commands need `-s <serial>` to target a device.

## Development Conventions

- Organize new features under `ui/<feature>`, with page-private components in the matching feature directory.
- Put data models in `data/model` and persistence logic in `data/repository`.
- Keep WebView logic in `ui/remote/web`.
- Register new Activities in the Manifest and launch them through the target Activity's `start(...)` method.
- User-facing text and code comments are in Chinese, following the existing project style.
- Before changing WebView injection rules, confirm the remote DOM structure first, then verify against the real page via emulator/CDP.
- Create a Chinese Git commit once each related group of changes is complete and verified.

## Contributing

Issues, suggestions, and pull requests are welcome.

### Reporting an Issue

Please include as much of the following as possible:

- Phone or emulator model
- Android version
- App version or APK build time
- Steps to reproduce
- Screenshots or screen recordings
- Relevant logs (remember to remove remote links, device IDs, tokens, and other sensitive information)

### Submitting a Pull Request

1. Fork this project and create a separate branch, for example `feature/xxx` or `fix/xxx`.
2. Keep the scope of changes focused; avoid mixing unrelated refactoring into a bug fix.
3. For Android UI changes, verify on an emulator or a real device.
4. For WebView injection changes, verify both the native layer (adb / screenshots) and the CDP page layer, and describe your verification environment and results in the pull request.
5. Provide a clear commit message and test results.

## License

This project is released under the [MIT License](LICENSE).
