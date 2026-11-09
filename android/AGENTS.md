# android/ — Android App

Kotlin + Jetpack Compose。先读根目录的 `AGENTS.md`，M2 的实施方案见 `docs/plans/m2-android.md`。

> 状态：骨架（M2 T2）、证书校验层（T3）、主机与凭据存储（T4）已完成。目录中的 `data/`、`service/` 等在后续任务中逐步加入；如有调整，同步更新本文件。

## 基线（ADR 0004、0011）

applicationId `io.github.qianyeeew.dshremote`；minSdk 26；compileSdk 37（SDK 包 `platforms;android-37.0`）；targetSdk 35；用 JDK 21 构建，编译目标 Java 17。依赖版本全部固定在 `gradle/libs.versions.toml`。

## 命令

```sh
cd android
./gradlew test                     # JVM 单元测试（CI 也跑）
./gradlew lint ktlintCheck         # lint（CI 也跑）
./gradlew assembleDebug            # 构建 debug APK（CI 也跑）
./gradlew connectedDebugAndroidTest  # 在已连接的真机上跑测试（只在本地跑）：src/sharedTest 的证书校验测试、androidTest 的 Keystore 测试，以及之后的界面测试
./gradlew devServerTest            # 对插件的 dev-server 跑集成测试（只在本地跑；T5 起加入）
```

构建需要 JDK 21 和 Android SDK 的 `platforms;android-37.0`（`sdkmanager --install "platforms;android-37.0" "build-tools;37.0.0"`），`ANDROID_HOME` 指向 SDK 目录。CI 见 `.github/workflows/android.yml`。

- lint 把警告也当成错误（`warningsAsErrors`）。关掉的检查写在 `app/build.gradle.kts` 和 `app/lint.xml` 中，每一项都注明了原因；不要为了让构建通过而随手关闭检查。
- ktlint 的规则在 `android/.editorconfig`（`ktlint_official` 风格，Compose 函数允许大写开头）。

**提交前**：`test`、`lint ktlintCheck`、`assembleDebug` 必须通过；改了证书校验（`data/net/`）、界面、Keystore 或前台服务时，还要在真机上跑 `connectedDebugAndroidTest`。

**测试目录**：`app/src/test/` 只在 JVM 上跑；`app/src/androidTest/` 只在真机上跑；`app/src/sharedTest/` 同时加入两者，同一组测试在电脑的 JDK 和手机的 Conscrypt 上各跑一遍（证书校验的测试放在这里）。AGP 9 内置 Kotlin，给 source set 加目录要用 `kotlin.directories`，用 `java.srcDir` 时 `.kt` 文件不会被编译。

## 技术选型（要改需要先写 ADR）

| 用途 | 选型 |
|---|---|
| UI | Jetpack Compose + Material 3 |
| 架构 | 单 Activity，MVVM（ViewModel + StateFlow），Kotlin 协程 |
| 网络 | OkHttp（HTTP + WebSocket） |
| JSON | kotlinx.serialization |
| 依赖注入 | 暂不引入框架，手动注入；规模变大后再考虑 Hilt |
| 本地存储 | DataStore。敏感数据（token、指纹）用 Android Keystore 中的 AES-GCM 密钥加密后再存。不使用已废弃的 `androidx.security:security-crypto` |
| Markdown | `com.mikepenz:multiplatform-markdown-renderer`（纯 Compose，带代码高亮模块） |
| 扫码 | CameraX + ZXing core（不用 ML Kit：它依赖 Google Play 服务，很多国产手机没有） |
| 后台 | 前台服务（`specialUse` 类型，ADR 0009）+ 本地通知 |
| 测试 | JUnit、OkHttp MockWebServer（含 `okhttp-tls` 生成自签证书）、kotlinx-coroutines-test；界面测试用 Compose UI Test |

## 目录（计划）

```
android/app/src/main/java/io/github/qianyeeew/dshremote/
├── data/
│   ├── net/        # 唯一的证书校验点（T3 已完成）：Fingerprint、PinnedTls（两道检查）、ClientFactory、PairingProbe
│   ├── host/       # （T4 已完成）Host、HostAddress、DeviceToken、PairUri（二维码解析）、SecretBox（Keystore AES-GCM）、HostRepository
│   ├── api/        # protocol.md 的 REST 接口和数据结构（外观层，不是 DSH 的协议）
│   └── live/       # 每台主机一条 /v1/stream 连接：重连、hello、订阅、事件分发
├── service/        # 前台服务、通知
└── ui/             # 各页面：主机列表与配对、会话列表、会话详情、审批与提问、设置
```

## 约定

- **证书校验只能写在一个地方**（`data/net/`），所有网络请求都必须经过它。不允许出现第二个绕过校验的 OkHttpClient；每台主机的客户端都由 `data/net/` 中的工厂创建。HostnameVerifier 的写法见 `docs/security.md`「App 端的证书校验」。
- 指纹校验必须有单元测试，至少覆盖：指纹正确时通过、指纹错误时拒绝、没有证书时拒绝，以及 TrustManager 和 HostnameVerifier 各自单独都能拦住错误的指纹。
- token 和指纹只能存在 Keystore 加密的存储里，不允许写进 SharedPreferences 明文或日志。持有 token 的类型不得在 `toString()` 中输出 token；OkHttp 不加会记录请求头的日志拦截器。
- 只调用 `docs/protocol.md` 中列出的接口；WebSocket 握手不发送 `Origin`。
- 字符串放进 `strings.xml`，不要硬编码，为第二版的中英文界面做准备。
- 开发界面时连接插件的 dev-server（`cd plugin && npm run dev-server`，M2 的 T1），不需要每次都配置真的模型。

## 真机

用户提供一台测试真机（Android 17），通过无线调试连接这台开发机：`adb devices` 能看到就直接用；看不到时用 `adb mdns services` 找 `_adb-tls-connect._tcp` 的地址，再 `adb connect <ip>:<端口>`。只对本 App 做安装、卸载、测试和截图，不动手机上的其他 App 和数据。
