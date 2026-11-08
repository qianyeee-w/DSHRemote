# 0010 Android 构建基线：targetSdk 35，applicationId `io.github.qianyeeew.dshremote`

- 状态：已被 [0011](0011-android-compile-sdk-37.md) 取代
- 日期：2026-11-09

## 背景

ADR 0004 只确定了 minSdk 26。建立 Android 工程前，还需要确定 applicationId、compileSdk、targetSdk 和构建工具链。

## 决策

| 项目 | 取值 |
|---|---|
| applicationId | `io.github.qianyeeew.dshremote`（由 GitHub 用户名 `qianyeee-w` 去掉连字符而来） |
| minSdk | 26（ADR 0004） |
| compileSdk / targetSdk | 35（Android 15） |
| JDK | 用 JDK 21 运行构建，编译目标为 Java 17 |
| AGP、Kotlin、Compose BOM 等 | 建立骨架时取当时最新的稳定版，固定在 `android/gradle/libs.versions.toml` 中，并提交 Gradle wrapper |
| 版本号 | 从 `0.1.0` 开始，和配对请求中的 `appVersion` 一致 |
| 签名 | 开发阶段使用 debug 签名。release 签名密钥由用户生成和保管，不得提交到仓库 |

## 理由

- **applicationId 发布后不能修改**，改了就成了另一个 App，所以先定下来。
- 开发机上已安装的 SDK 是 35。targetSdk 35 已经足够新，同时可以暂时不受 Android 16、17 新行为的影响。
- Android 17 对 targetSdk ≥ 37 的 App 强制要求本地网络权限（`ACCESS_LOCAL_NETWORK`），连接局域网地址前必须先在运行时申请。v1 的 targetSdk 35 不受影响。

## 代价 / 后果

- 以后升级 targetSdk 要单独提交，并新写一条 ADR。升级到 37 及以上时，**必须同时处理本地网络权限**，否则 App 连不上局域网中的电脑。
- targetSdk 35 下系统强制全面屏（edge-to-edge），界面需要自己处理状态栏和导航栏的边距。
