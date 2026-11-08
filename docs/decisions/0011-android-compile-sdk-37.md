# 0011 Android 构建基线：compileSdk 37，targetSdk 仍为 35

- 状态：已接受
- 日期：2026-11-09
- 取代：[0010](0010-android-build-baseline.md)

## 背景

ADR 0010 定的是 compileSdk / targetSdk 都为 35。M2 T2 建立骨架时发现，当前的 AndroidX 库要求更高的 compileSdk：`core` 1.19 要求 37，`activity` 1.13 要求 36，Compose BOM 2026.09 中的 Compose UI 也要求 35 以上。用 compileSdk 35 构建时，AGP 会直接报错。

## 决策

| 项目 | 取值 |
|---|---|
| applicationId | `io.github.qianyeeew.dshremote`（同 0010） |
| minSdk | 26（ADR 0004） |
| **compileSdk** | **37**（SDK 包 `platforms;android-37.0`） |
| **targetSdk** | **35**（同 0010） |
| JDK | 用 JDK 21 运行构建，编译目标为 Java 17（同 0010） |
| AGP、Kotlin、Gradle、Compose BOM 等 | 建立骨架时取当时最新的稳定版，固定在 `android/gradle/libs.versions.toml` 中，并提交 Gradle wrapper（同 0010） |
| 版本号、签名 | 同 0010 |

## 理由

- **compileSdk 只决定编译时能看到哪些 API，不改变 App 在手机上的行为**；运行时的行为由 targetSdk 决定。所以把 compileSdk 升到 37，0010 中「暂时不受 Android 16、17 新行为影响」的考虑仍然成立，Android 17 的本地网络权限也仍然不会生效。
- 另一条路是保持 compileSdk 35，把 AndroidX 库降到一年多前的版本。这样会错过 bug 修复，以后升级也更麻烦。

## 代价 / 后果

- 开发机和 CI 都需要安装 `platforms;android-37.0`。
- 代码中调用新于 minSdk 26 的 API 时，lint 会检查是否加了版本判断（`NewApi`），和以前一样。
- 升级 targetSdk 的注意事项同 0010：升级到 37 及以上时，必须同时处理本地网络权限（`ACCESS_LOCAL_NETWORK`）。
