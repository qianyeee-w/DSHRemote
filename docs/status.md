# 项目现状与交接

> 给新加入的 agent：读完根目录的 `AGENTS.md` 后读这一份。它记录项目做到了哪里、我们怎么协作，以及开发中踩过的坑。每完成一个阶段或做出重要决定，都要更新这份文件。

最后更新：2026-11-09（M2 T7 完成）

## 1. 进度

| 阶段 | 状态 | 文档 |
|---|---|---|
| 需求与设计（spec、protocol、security、ADR 0001–0008） | 完成 | `docs/` |
| DSH 接口调研 | 完成 | `docs/research/dsh-session-api.md` |
| **M1 电脑端插件** | **完成**（T1–T10，Windows 官方 Desktop 已真机验证） | `docs/plans/m1-plugin.md`（第 6 节是验收结果和遗留问题） |
| **M2 Android App** | **进行中**：T1（插件 dev-server）、T2（Android 骨架 + CI）、T3（证书校验层）、T4（主机与凭据存储）、T5（协议客户端）、T6（实时连接）、T7（配对与主机界面）、T8（会话列表与新建会话）、T9（会话详情）完成，下一步 T10（审批与提问） | `docs/plans/m2-android.md` |

**下一步**：按 `docs/plans/m2-android.md` 第 5 节的顺序做 T1–T12，每完成一项汇报一次。

M2 开发要知道：

- `android/` 的骨架已建立（T2）：Gradle 版本目录、lint（警告即错误）、ktlint、禁止明文流量的配置及其单元测试、CI（`android.yml`）。命令和约定见 `android/AGENTS.md`。
- **开发 App 时连接 dev-server**（`cd plugin && npm run dev-server`，T1 完成）：真实的 `dsh web` + 本插件 + 假模型，消息里的关键词决定模型的行为（ASK 提问、SHELL 审批、STREAM 流式、THINK 思考、SLOW 长输出）。默认局域网端口 3290、配对页 3291，**不用 3190 / 3191**：用户的开发服务器上，3190 被用户自己的 `dsh web` 服务占用。数据目录 `~/.cache/dshremote-dev` 保存指纹和已配对设备，重启后手机不需要重新配对。`npm run dev-server -- pair` 打印配对二维码，`restart` 重启 DSH，`revoke` 撤销设备。测试代码一律用 `dsh-runtime.mjs` 的 `freePort()` 找空闲端口。
- **证书校验层**在 `android/.../data/net/`（T3）：App 的所有请求都必须用 `ClientFactory.forPin(指纹)` 创建的客户端；手动配对用 `PairingProbe` 取指纹。它的测试放在 `app/src/sharedTest/`，同一组测试在 JVM 和真机上各跑一遍；改动这一层后必须在真机上跑 `connectedDebugAndroidTest`。
- **主机与凭据**在 `android/.../data/host/`（T4）：`HostRepository` 保存主机列表，指纹和 token 用 Keystore 密钥加密（`SecretBox`）；`PairUri` 严格解析配对二维码。持有 token 的类型（`DeviceToken`、`PairUri` 的配对码）的 `toString()` 都不输出秘密。
- **协议客户端**在 `android/.../data/api/`（T5）：`PluginApi` 实现 protocol.md 的全部 REST 接口，失败时抛出 `ApiException`（`Http` / `FingerprintMismatch` / `Network` / `BadResponse`）。发消息用 `OutgoingMessage`，重试时传同一个对象（requestId 不变）。`./gradlew devServerTest` 对 dev-server 跑配对、只读接口、发消息去重和取消配对。
- **实时连接**在 `android/.../data/live/`（T6）：`ConnectionManager` 跟随主机列表，每台主机一条 `HostConnection`；只有存在 `hold()` 时才连接（界面可见、前台服务各持有一个）。连接时先用 `/v1/info` 选地址（lastGoodAddress 优先，失败后其他地址同时试），通过后才用 token 打开 WebSocket。状态 `LinkStatus` 中 `isTerminal` 的几种（需要重新配对、指纹不匹配、App 版本太旧）不会自动重连；指纹不匹配在网络变化后会再试一次。会话详情页用 `HostConnection.subscribe(sessionId)` 收消息，收到 `SessionEvent.Resync` 时重新读取最新一页历史。`devServerTest` 里的 `LiveDevServerTest` 会重启 dev-server 中的 DSH 并撤销自己的测试设备。
- **配对与主机界面**（T7）：`DshRemoteApp` 的 `AppContainer` 手动创建 `HostRepository`、`ConnectionManager`、`PairingService` 并注册 `NetworkMonitor`；`MainActivity` 可见时持有 `hold()`。配对逻辑在 `android/.../data/pairing/PairingService`（同一台电脑再次配对时更新已有主机并撤销旧设备，见 `m2-android.md` 3.2）。界面在 `ui/hosts/`、`ui/pairing/`，用 Navigation Compose 的类型安全路由（`ui/AppNavigation.kt`）；配对码只在 ViewModel 内存中。只有一台主机时首页直接是它的会话列表，有多台时是主机列表；主机管理页从会话列表的菜单进入。
- **会话列表**（T8，`ui/sessions/`）：会话来自实时连接的状态（`hello` 和之后的 `session.*` 事件），不单独请求 `/v1/sessions`。新建会话等 REST 调用用 `ConnectionManager.api(hostId)`：只在在线时可用，地址是连接正在用的地址。会话详情页（`ui/chat/`）在 T8 中是占位页面。
- **会话详情**（T9）：合并规则在 `android/.../data/chat/ChatTimeline`，本地发出的消息在 `Outbox`，界面在 `ui/chat/`（`ChatViewModel` 通过 `ChatBackend` 访问实时连接和接口）。Markdown 用 `multiplatform-markdown-renderer` 0.46.0（m3 + 代码高亮）。设计要点见 `m2-android.md` 3.4「实现要点」。假模型新增 `MARKDOWN` 关键词（输出标题、列表、长代码块、宽表格和链接）。T10 在会话详情页底部加入审批和提问。
- **时钟差**：手机和电脑的时钟可能差很多（开发时这台服务器和手机就差了 8 小时），会话的 `updatedAt` 来自电脑。App 用 `/v1/info` 响应的 `Date` 头估算时钟差（`HostLiveState.clockOffsetMillis`），显示「多久以前」时用 `ui/common/ServerClock.kt` 换算。以后显示电脑给的时间都要这样处理。
- **在真机上发消息做验证**：手机上是中文输入法，`adb shell input text` 打的字母会被输入法吞掉，发不出去。需要让某个会话收到消息时，用一台临时测试设备通过插件接口发送（配对、发送、撤销，配对码和 token 只在内存中），或者请用户在手机上输入。不要为此修改手机的输入法设置。
- **测试真机**：用户提供一台 Android 17 的小米手机，通过无线调试连接开发机（用法见 `android/AGENTS.md`「真机」）。手机和开发机在同一局域网，已确认手机能连到 dev-server 的 3290 端口。
- 插件对外的接口以 `docs/protocol.md` 为准，已经按实现定稿，没有待定项。App 只能使用其中列出的接口。
- App 端的安全要求在根目录 `AGENTS.md` 的安全红线和 `android/AGENTS.md` 中，尤其是：证书指纹不匹配时直接拒绝，**不提供「仍然继续」**。

### 待办（插件）

- **端口被占用时，用户看不到任何提示。** 插件的局域网端口或配对页端口被其他程序占用时，插件会启动失败，但 DSH 仍然显示插件为 active，也不打印错误。T10 在 Windows 上遇到过一次（VS Code 端口转发），交接时在 Linux 服务器上又遇到一次。`npm run load-check` 现在会直接指出端口被占用，但官方 Desktop 的用户仍然什么都看不到。
  - 建议的改法：局域网端口启动失败时，配对页照常启动，并在页面上显示「端口 X 被占用」以及处理方法。配对页端口也被占用时，至少要让插件进入失败状态，不要显示为 active（需要先确认 DSH 怎么判断插件失败）。
  - 改之前先和用户确认方案。

## 2. 协作方式（用户的习惯）

- 全程用**中文**交流，文档、注释、提交信息也用中文。
- **一次只做一个任务。** 每个任务做完后，向用户汇报，等用户回复「继续」再做下一个。
- **较大的改动先给方案**（改哪些文件、怎么测试），用户确认后再动手。需求不清楚时先问，不要自行扩大范围（以 `spec-v1.md` 为准）。
- **直接推送到 `main`**，不开 PR。所以推送前必须在本地跑完相关测试（见 `plugin/AGENTS.md`、`android/AGENTS.md` 的「提交前」）。推送后用 `gh` 查看 CI，全部通过后再汇报。
- 提交信息：`<type>(<scope>): <中文摘要>`。正文写清楚做了什么、为什么。
- **汇报的写法**：先说结果，再说做了什么、发现了什么问题、有什么限制，最后说下一步做什么并询问是否继续。没有实际跑通的测试，不要说已经完成。
- 需要用户在真机上操作时（例如 Windows 上的 Desktop、手机），写成一份步骤清单，或者一段给用户本地 agent 的提示词，并说明哪些步骤要用户亲自做。参考 `docs/plans/m1-windows-verification.md`。

## 3. 开发中学到的经验

DSH 接口的细节都记录在 `docs/research/dsh-session-api.md` 第 5 节（5.6–5.8 是实现 M1 时确认的）。下面是最容易踩的几条：

- **「看起来对」的测试不够，要在真实环境里测。** M1 中以下三个问题，单元测试都没有发现：
  - DSH 0.2.2-rc.1 的源码里有按 `requestId` 去重的逻辑，实测却不去重。是契约测试发现的。
  - 插件同时注册两个事件监听者，打开了两条 `$events` 流，导致回答提问被误判为 409。是端到端测试发现的。
  - 配对页的 `Referrer-Policy: no-referrer` 让浏览器提交表单时发送 `Origin: null`，结果无法生成配对码。是 Windows 真机验证发现的，单元测试用 Node 客户端手动设置了 Origin，所以测不出来。

  所以涉及 DSH 行为的结论，要用契约测试（真实的 `dsh web`）验证；涉及浏览器的，要用真实的浏览器验证（`npm run browser-check`）；涉及手机的，要在真机上验证。
- **DSH 的多客户端行为**：
  - 每条 `$events` 流都算一个独立的客户端。
  - 一个客户端回答了审批，其他客户端会收到 `cancel`。
  - 已经被处理掉的事件，再回答时 DSH 静默返回成功。所以插件靠 `cancel` 帧来判断 409。
- **Windows 上的实际情况**：
  - 用 VS Code Remote-SSH 时，VS Code 会把服务器上监听的端口自动转发到 Windows 本机，可能占用插件的端口。
  - 装了 VMware、VirtualBox、WSL 的电脑，会有很多虚拟网卡。
  - 防火墙放行时只勾「专用网络」。
- **DSH 切换模型时不发出会话事件**：插件原来只在收到 `api-session/*` 事件时推送 `session.updated`（另有 30 秒定时刷新），切换模型后手机要等最多 30 秒才更新。现在切换模型和权限模式成功后插件主动刷新（`LiveHub.refreshSoon()`）。插件依赖 DSH 事件的地方，都要在 devServerTest 或 e2e 里确认事件真的会来。
- **DSH 会把工作目录所在的整个 git 仓库快照到系统临时目录**（`dsh-workspace-changes-*`，每一轮结束时）。开发服务器的 home 目录本身就是一个 git 仓库，dev-server 的工作目录又在 `~/.cache` 下，于是每个会话都复制一遍整个 home 仓库：T5 时把 7.7G 的 `/tmp`（内存盘）写满了，每一轮还要多等约 30 秒才变为空闲。现在 dev-server 把工作目录 `git init` 成独立的空仓库，一轮结束后约 1 秒就空闲。`/tmp` 被写满时先停掉 dev-server，DSH 退出时会清理自己的临时目录。
- **Android 构建和 TLS**：
  - AGP 9 内置了 Kotlin 支持。给 source set 加目录要用 `kotlin.directories.add(...)`；放在 `java.srcDir(...)` 里的 `.kt` 文件不会被编译，测试会**静默地不运行**（构建照样成功）。加测试后要看一眼测试报告里的数量。
  - 手机上的 TLS 实现（Conscrypt）和电脑上的 JDK 有差别。例如握手失败后，Conscrypt 的 session 仍然带着对端证书，JDK 的不带。所以 TLS 相关的测试必须在真机上也跑一遍。
  - MockWebServer 服务器端的 WebSocket 不支持 `cancel()`（会抛 NullPointerException）。测试中要模拟「突然断线」，用 `FakePlugin.ServerSide.kill()`（调用 OkHttp 内部的 `failWebSocket`）。
  - Compose UI Test 默认带的 Espresso 3.5 在 Android 15 以上的系统中调用已经删除的 `InputManager.getInstance`，所有界面测试都会失败。`libs.versions.toml` 中显式指定了 Espresso 3.7.0。界面测试还要求手机**解锁**，否则报 `No compose hierarchies found`。`connectedDebugAndroidTest` 跑完默认会卸载 App，手机上的配对随之丢失（电脑上留下一条用不了的设备记录）：要加 `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`。
  - JUnit 4 的 `@BeforeClass` / `@Test` 方法必须返回 void：用 `runBlocking` 时写成 `runBlocking<Unit>`，否则最后一行有返回值时整个测试类报 `initializationError`。
- **DSH 源码**：做接口相关的工作时，需要查看 DSH 的源码。仓库是 `https://github.com/deepseek-ai/deepseek-harness`，标签 `dsh-v0.2.2-rc.1`。可以克隆到项目外的目录中参考，不要放进本仓库。社区项目 dsh-Go 是 AGPL 许可证，**只能看接口名，不能复制代码**。

## 4. 测试命令速查

插件（在 `plugin/` 下，详见 `plugin/AGENTS.md`）：

| 命令 | 用途 |
|---|---|
| `npm run check` | lint + 类型检查 + 单元测试，提交前必须通过 |
| `npm run load-check` | 装进真实的 `dsh web`，确认插件能加载、能连上 DSH |
| `npm run contract` | 契约测试：假模型驱动真实的 `dsh web`，逐项验证插件依赖的 DSH 接口 |
| `npm run e2e` | 端到端测试：模拟手机走完整个流程 |
| `npm run browser-check` | 用真实的浏览器操作配对页 |
| `node scripts/verify.mjs` | 真机验证（见 `docs/plans/m1-windows-verification.md`） |

`load-check`、`contract`、`e2e` 需要 PATH 上有 pnpm，第一次运行时会自动下载 DSH。设置 `DSH_INSTALL_DIR=<目录>` 可以复用已经下载的 DSH。
