# android/ — Android App

Kotlin + Jetpack Compose。先读根目录的 `AGENTS.md`，M2 的实施方案见 `docs/plans/m2-android.md`。

> 状态：骨架（M2 T2）、证书校验层（T3）、主机与凭据存储（T4）、协议客户端（T5）、实时连接（T6）、配对与主机界面（T7）、会话列表与新建会话（T8）、会话详情（T9）、审批与提问（T10）、前台服务与通知（T11）已完成。目录中的 `data/`、`service/` 等在后续任务中逐步加入；如有调整，同步更新本文件。

## 基线（ADR 0004、0011）

applicationId `io.github.qianyeeew.dshremote`（debug 包为 `io.github.qianyeeew.dshremote.debug`，应用名「DSHRemote 调试」，可以和 release 包同时安装）；minSdk 26；compileSdk 37（SDK 包 `platforms;android-37.0`）；targetSdk 35；用 JDK 21 构建，编译目标 Java 17。依赖版本全部固定在 `gradle/libs.versions.toml`。

**版本号和签名（ADR 0012）**：版本号只在 `gradle.properties` 的 `dshremote.version` 中定义（App 和插件共用），versionCode 由它算出（`VersionTest` 检查公式）。release 包只能用正式发布密钥签名，签名信息只从环境变量 `DSHREMOTE_KEYSTORE`、`DSHREMOTE_KEYSTORE_PASSWORD`、`DSHREMOTE_KEY_ALIAS` 读取，缺少时 `assembleRelease` 直接失败。密钥由用户运行 `scripts/create-release-key.sh` 生成，**agent 不要运行这个脚本，也不要接触密钥和密码**；证书指纹在 `release-cert.sha256`。不开启 R8。

## 命令

```sh
cd android
./gradlew test                     # JVM 单元测试（CI 也跑）
./gradlew lint ktlintCheck         # lint（CI 也跑）
./gradlew assembleDebug            # 构建 debug APK（CI 也跑），包名带 .debug 后缀
./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
                                   # 在已连接、已解锁的真机上跑测试（只在本地跑）：src/sharedTest 的证书校验测试、androidTest 的 Keystore 测试和界面测试。
                                   # 不加这个参数时，跑完会卸载 App，手机上的配对也跟着丢失
./gradlew connectedDebugAndroidTest -Pdshremote.debugApplicationIdSuffix=.projecttest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
                                   # 手机已有其他签名的 .debug 包时，用隔离包名测试，不覆盖原 App
./gradlew devServerTest            # 对插件的 dev-server 跑集成测试（只在本地跑）：先在 plugin/ 下 npm run dev-server。会重启 dev-server 中的 DSH
```

构建需要 JDK 21 和 Android SDK 的 `platforms;android-37.0`（`sdkmanager --install "platforms;android-37.0" "build-tools;37.0.0"`），`ANDROID_HOME` 指向 SDK 目录。CI 见 `.github/workflows/android.yml`。

- lint 把警告也当成错误（`warningsAsErrors`）。关掉的检查写在 `app/build.gradle.kts` 和 `app/lint.xml` 中，每一项都注明了原因；不要为了让构建通过而随手关闭检查。
- ktlint 的规则在 `android/.editorconfig`（`ktlint_official` 风格，Compose 函数允许大写开头）。
- JVM 单元测试在运行时使用同版本的 `datastore-core-jvm`，避免 Android 存储后端在 Windows 的 mockable android.jar 中走不支持覆盖文件的 `renameTo`。APK 和真机测试仍使用 Android 后端；不要通过跳过存储测试来处理平台差异。
- 调试包默认后缀仍为 `.debug`。手机上已有其他签名的调试包时，可通过 `dshremote.debugApplicationIdSuffix` 指定隔离后缀（例如 `.projecttest`），真机测试不会替换原包。

**提交前**：`test`、`lint ktlintCheck`、`assembleDebug` 必须通过；改了 `data/api/` 或之后的实时连接时，还要启动 dev-server 跑 `devServerTest`；改了证书校验（`data/net/`）、界面、Keystore 或前台服务时，还要在真机上跑 `connectedDebugAndroidTest`。

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
| 导航 | Navigation Compose（类型安全的路由；路由参数会进入 SavedState，只放 id 之类的非秘密信息） |
| 扫码 | CameraX + ZXing core（不用 ML Kit：它依赖 Google Play 服务，很多国产手机没有） |
| 后台 | 前台服务（`specialUse` 类型，ADR 0009）+ 本地通知 |
| 测试 | JUnit、OkHttp MockWebServer（含 `okhttp-tls` 生成自签证书）、kotlinx-coroutines-test；界面测试用 Compose UI Test |

## 目录（计划）

```
android/app/src/main/java/io/github/qianyeeew/dshremote/
├── data/
│   ├── net/        # 唯一的证书校验点（T3 已完成）：Fingerprint、PinnedTls（两道检查）、ClientFactory、PairingProbe
│   ├── host/       # （T4 已完成）Host、HostAddress、DeviceToken、PairUri（二维码解析）、SecretBox（Keystore AES-GCM）、HostRepository
│   ├── api/        # （T5 已完成）PluginApi：protocol.md 的 REST 接口、数据结构、错误码（外观层，不是 DSH 的协议）
│   ├── attention/  # （T10 已完成）AnswerForm：回答提问前的本地校验（和插件的规则一致）
│   ├── chat/       # （T9 已完成）ChatTimeline（历史分页和推送的合并规则）、Outbox（发出但还没出现在日志里的消息）
│   ├── live/       # （T6 已完成）ConnectionManager、HostConnection：每台主机一条 /v1/stream 连接，选地址、重连、hello、订阅、事件分发
│   ├── settings/   # （T11 已完成）SettingsRepository：通知开关等设置（DataStore Preferences，不含秘密）
│   └── pairing/    # （T7 已完成）PairingService：探测指纹、配对、重新配对、取消配对（DELETE /v1/me）
├── service/        # （T11 已完成）LinkService（specialUse 前台服务）、LinkController（启停）、NotificationRules（通知规则，纯逻辑）、Notifier
├── ui/             # 各页面。AppNavigation（路由）、hosts/（主机列表、主机管理）、pairing/（扫码、手动输入、指纹核对）、
│                   # sessions/（会话列表、新建会话）、chat/（会话详情：消息、Markdown、输入框、模型和权限模式）、
│                   # attention/（审批和提问的面板、独立处理页面、AttentionHandler）、settings/（电脑与设置、后台运行提示）、common/（通用组件）
├── DshRemoteApp.kt # Application：AppContainer 手动创建 HostRepository、ConnectionManager、PairingService，注册 NetworkMonitor
└── MainActivity.kt # 唯一的 Activity；可见时（onStart 到 onStop）持有 ConnectionManager.hold()
```

## 约定

创建／导入项目在 `ui/sessions/ProjectFlow.kt`（状态与恢复）和 `ProjectPicker.kt`（目录选择界面）。目录路径由电脑返回，手机不自行拼接 Windows／Linux 路径。创建回执不明时禁止自动重发；已知目录已创建时只重试登记。协议与验收见 `docs/plans/project-create-import.md`。

- **证书校验只能写在一个地方**（`data/net/`），所有网络请求都必须经过它。不允许出现第二个绕过校验的 OkHttpClient；每台主机的客户端都由 `data/net/` 中的工厂创建。HostnameVerifier 的写法见 `docs/security.md`「App 端的证书校验」。
- 指纹校验必须有单元测试，至少覆盖：指纹正确时通过、指纹错误时拒绝、没有证书时拒绝，以及 TrustManager 和 HostnameVerifier 各自单独都能拦住错误的指纹。
- token 和指纹只能存在 Keystore 加密的存储里，不允许写进 SharedPreferences 明文或日志。持有 token 的类型不得在 `toString()` 中输出 token；OkHttp 不加会记录请求头的日志拦截器。
- 只调用 `docs/protocol.md` 中列出的接口；WebSocket 握手不发送 `Origin`。
- 界面的颜色和字号只从主题取：`DesignTheme.colors`（设计规格第 3 节的颜色）、`MaterialTheme.typography`、`CodeTextStyles`，不要在页面里写死颜色值。通用组件在 `ui/common/DesignComponents.kt`，设计依据是 `docs/design/ui-redesign/`。
- 字符串放进 `strings.xml`，不要硬编码，为第二版的中英文界面做准备。lint 的 `UnusedResources` 也算错误，删掉界面时记得删掉不再使用的字符串。
- 页面拆成两层：`XxxScreen` 连接 ViewModel，`XxxContent` 只接收状态和回调。界面测试（`androidTest`）直接渲染 `XxxContent`，不需要网络和真实数据。
- 界面测试要在**解锁的**手机上跑：锁屏时测试用的 Activity 不会显示，Compose 测试报 `No compose hierarchies found`。
- 开发界面时连接插件的 dev-server（`cd plugin && npm run dev-server`，M2 的 T1），不需要每次都配置真的模型。

## 真机

用户提供一台测试真机（Android 17），通过无线调试连接这台开发机：`adb devices` 能看到就直接用；看不到时用 `adb mdns services` 找 `_adb-tls-connect._tcp` 的地址，再 `adb connect <ip>:<端口>`（手机锁屏后无线调试会断开，端口会变）。只对本 App 做安装、卸载、测试和截图，不动手机上的其他 App 和数据。

需要人工确认的项目（锁屏通知、切换网络、撤销等）按 `docs/plans/m2-device-verification.md` 走。手机上是中文输入法，`adb shell input text` 打不了字母：要让会话收到消息、模拟 Desktop 处理审批、模拟证书不同的设备时，用 dev-server 的 `send`、`decide`、`impostor` 子命令（见 `plugin/AGENTS.md`）。
