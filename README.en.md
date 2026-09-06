# ZCode Mobile App

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-API%2024%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![WebView](https://img.shields.io/badge/Chromium-WebView-4285F4?logo=googlechrome&logoColor=white)](https://developer.android.com/develop/ui/views/layout/webapps/webview)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[Simplified Chinese](README.md) | **English**

ZCode Mobile App is an Android client for ZCode Remote Control.

ZCode Mobile App wraps the official remote control page in a native shell: it uses an app container instead of the mobile browser to host the page, and adapts it for phone screens, touch interaction, and system behavior, making remote control more stable and usable on mobile.

> Note: This app does not replace ZCode's remote execution capabilities. It focuses on connection management, the native container, and mobile interaction optimization.

<table>
  <tr>
    <td><img src="https://github.com/my-dlq/zcode-mobile-app/blob/main/images/zmobile-01.png" alt="Screenshot 1"></td>
    <td><img src="https://github.com/my-dlq/zcode-mobile-app/blob/main/images/zmobile-02.png" alt="Screenshot 2"></td>
    <td><img src="https://github.com/my-dlq/zcode-mobile-app/blob/main/images/zmobile-03.png" alt="Screenshot 3"></td>
    <td><img src="https://github.com/my-dlq/zcode-mobile-app/blob/main/images/zmobile-04.png" alt="Screenshot 4"></td>
  </tr>
</table>

## Features

- **Security:** Adds fingerprint and pattern unlock.
- **Theme:** Built-in light and dark themes, switchable in settings.
- **Event notifications:** Supports four event types — task success, failure, approval, and question — each can be enabled independently.
- **Session memory:** Remembers the last task session per device and restores that device and session after switching back or after the process is reclaimed by the system.
- **In-app updates:** Silently checks GitHub Releases on startup, downloads the APK and launches the system installer, with an option to ignore a specific version.
- **Connection management:** Add remote devices quickly via four entry points (QR scan, gallery recognition, clipboard detection, and deep links), with unified management of multiple connections and sorting support.
- **Mobile interaction:** Immersive full screen, narrow-screen adaptation, mis-touch suppression, settings UI optimization, staged back-key handling, and auto-raising the page when the soft keyboard appears, for a better experience.
- **Background keep-alive:** Keeps the connection alive with a foreground service plus a screen-off `WakeLock` during remote sessions, preventing the page from being reclaimed by the system, and provides battery optimization guidance.

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

Debug APK output path:

```text
app/build/outputs/apk/debug/app-debug.apk
```

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

### Submitting an Issue

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

## Disclaimer

- This project is an independent, community-developed third-party open-source client with **no affiliation** to [ZCode](https://zcode.z.ai) / Z.ai, and has received no official authorization, sponsorship, or endorsement.
- "ZCode", "Z.ai", and related names, trademarks, and logos belong to their respective owners; this project references them solely to denote the official product.
- The task progress, charts, terminal output, and other page content shown in the app are generated and hosted by ZCode's official remote service. The ownership and operation of remote addresses (such as `https://zcode.z.ai/remote`) belong entirely to ZCode. This project does not store or relay any remote-side data.
- This project adapts the official page for mobile display by injecting CSS/JS, which may break when the official page is redesigned; please do not attribute resulting display or usage issues to the official side.
- When using this project to connect to remote services, please follow ZCode's official terms of service and license agreements. Use is at your own risk.

## License

This project is released under the [MIT License](LICENSE).
