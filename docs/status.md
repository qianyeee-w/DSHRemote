# 项目现状与交接

> 给新加入的 agent：读完根目录的 `AGENTS.md` 后读这一份。它记录项目做到了哪里、我们怎么协作，以及开发中踩过的坑。每完成一个阶段或做出重要决定，都要更新这份文件。

最后更新：2026-11-13（新版界面第一批 A1–A6 完成，等用户确认后做第二批 B1；M3 进行中：T1–T5 完成，`v0.1.0-rc.1` 已发布；新增开发优先级建议）

## 1. 进度

| 阶段 | 状态 | 文档 |
|---|---|---|
| 需求与设计（spec、protocol、security、ADR 0001–0008） | 完成 | `docs/` |
| DSH 接口调研 | 完成 | `docs/research/dsh-session-api.md` |
| **M1 电脑端插件** | **完成**（T1–T10，Windows 官方 Desktop 已真机验证） | `docs/plans/m1-plugin.md`（第 6 节是验收结果和遗留问题） |
| **M2 Android App** | **完成**（T1–T12，小米 Android 17 真机验证，电脑端用 Linux 的 `dsh web`） | `docs/plans/m2-android.md`（第 6 节验收清单）、`docs/plans/m2-device-verification.md`（真机验证结果） |
| **M3 v1 发布** | **进行中**（T1–T5 完成，`v0.1.0-rc.1` 已发布） | `docs/plans/m3-release.md` |

**当前进度**：M3 T1–T5 已完成，预发布版 [`v0.1.0-rc.1`](https://github.com/qianyeee-w/DSHRemote/releases/tag/v0.1.0-rc.1) 已发布。下一步：

- 用户用 rc.1 在 Windows 官方 Desktop 和手机上走查（T7 的主要内容）。走查中发现的问题修进 rc.2；
- 然后做 T6（用户文档），发布 `v0.1.0`，验证从 rc 覆盖升级。

创建／导入项目（[`plans/project-create-import.md`](plans/project-create-import.md)）和 Android 视觉改版（[`plans/android-visual-refresh.md`](plans/android-visual-refresh.md)）已实现，自动化检查都通过。还差的人工验证并入 M3：Windows Desktop 上创建／导入的手动流程并入 T7；视觉改版的人工走查和截图并入 T6。M3 的范围仍是自用、私有仓库、中文文档。

**开发优先级**：M3 之后各项功能的排序见 [`roadmap.md`](roadmap.md) 开头的「开发优先级」。这份排序是 2026-11-12 的建议稿，**用户确认前不按它开工**。

**后续范围确认（2026-11-10）**：用户确认下一阶段纳入通知操作按钮和云推送；**锁屏时不能提交审批，必须先解锁，并在提交前重新校验事项状态**。前面讨论的可靠性、Diff、会话定位、计划／目标、终端和完整文件管理纳入后续设计，图片附件为备选，语音不纳入本阶段，中转与自动穿透暂不考虑。创建／导入项目已获实现方案确认，优先于 M3 剩余任务、完整文件管理和远程终端。桌面端用量、缓存命中、对话分支、过程折叠及截图中的其他统计已做源码与录制样本调研，见 [`DSH 接口调研`](research/dsh-session-api.md) 第 9 节；尚未实测新增统计接口或确认这些统计功能的排期。完整范围及设计约束见 [`roadmap.md`](roadmap.md) 第 3 节。

**新版界面设计（2026-11-12）**：用户确认了一套新的 App 界面设计，共十四屏，覆盖现有功能以及执行结果、未读、发送状态、计划、用量、图片附件、对话分支、限时提问。设计规格（截图、可在浏览器打开的样稿、规则说明）在 [`design/ui-redesign/`](design/ui-redesign/README.md)，落地方案在 [`plans/ui-redesign.md`](plans/ui-redesign.md)：第一批只改 Android，第二、三批要先扩展协议和插件。方案第 7 节的四个问题和 A2、A3 的几个细节都已确认（见方案）。**第一批 A1–A6（主题和组件、会话页、审批和提问面板、首页、新任务、电脑与设置）已完成**，结果见方案末尾，App 的样子已经和设计一致；第二批从 B1（轮次与执行结果，要先改协议和插件）开始。它取代 `plans/android-visual-refresh.md` 里的视觉约定。改界面之前先读设计规格。

**取消 Git 功能（2026-11-11）**：用户决定手机端不做 Git 提交与回滚。这类操作直接在对话里交给 Agent 完成，手机上可操作的空间也很小；[`roadmap.md`](roadmap.md) 第 3 节已同步。

**Android 视觉方向确认（2026-11-10）**：用户希望以「高效与 AI 对话」为核心，优先简洁、实用和长期阅读体验；已明确选择 **「正文舒适、控件紧凑」** 的信息密度。正文、Markdown 和代码保留阅读空间，操作区保持紧凑，同时让运行状态和待处理事项容易辨认。透明流体预览仅作风格探索，用户已放弃该方向。后续按确认稿完成了中性灰白主题、开发工具字体与字号、聊天输入框、工作区分组、主机和设置页，详见视觉改版方案。

**视觉预览细化（2026-11-10）**：按用户反馈，聊天稿去掉 AI 回复上方的图标和「DSH」标记，加入淡灰点阵背景；中文／英文界面使用 Noto Sans SC／Inter，代码使用 JetBrains Mono。用户提供 Codex 手机界面作为布局参考：项目下展开会话，输入框默认单行，编辑时将权限和模型放在框内第二行。用户确认会话首页、聊天详情、主机管理和设置四页方案，随后授权完整开发与真机测试。

M3 已完成：
- T1：DSH 新版本兼容性检测（`.github/workflows/dsh-compat.yml`，脚本 `plugin/scripts/dsh-compat.mjs`）。每天北京时间 9 点检查 npm 上 latest、next、alpha 中比基线新的版本，跑 load-check、contract、e2e，失败时开 issue「DSH <版本> 兼容性检测失败」。定时运行时跳过已经通过的版本和已经开过 issue 的版本；要重新检测某个版本，在 Actions 页面手动触发并填写版本号。收到这类 issue 后，按 issue 中「怎么处理」一节适配。
- T2：release 构建和签名（ADR 0012）。版本号只在 `android/gradle.properties` 的 `dshremote.version`；release 签名信息只从环境变量读取，缺少时打包失败；debug 包加 `.debug` 后缀。发布密钥由用户生成，在 GitHub Secrets 和用户的离线备份中，agent 不接触；证书指纹在 `android/release-cert.sha256`。
- T3：插件安装包 `dshremote-plugin-X.Y.Z.tgz`（`npm run pack`，`ws` 打包在内）。在 Desktop「添加插件」中填它的绝对路径即可安装；装好后 `.tgz` 不能删除或移动（`docs/research/dsh-plugin-install.md`）。CI 用安装包离线装进 `dsh web` 跑 load-check。
- T4：发布工作流 `.github/workflows/release.yml`。推送 `vX.Y.Z[-rc.N]` 标签时检查、构建、签名并创建 GitHub Release（rc 版标为预发布）；手动触发时只试运行。签名证书指纹必须和 `android/release-cert.sha256` 一致。版本号用 `node plugin/scripts/release.mjs bump <版本>` 一次改好三处，更新说明取自根目录的 `CHANGELOG.md`。release APK 有 34 MB（中文字体约 30 MB，没开 R8）；新版界面 A1 把中文字体换成 IBM Plex Sans SC 后，debug 包小了约 3.2 MB。
- T5：会话标题去掉开头的 Markdown 标记（`ui/common/SessionTitle.kt`），只改显示。以后新的地方显示会话标题时，用 `sessionTitleText()` 或 `sessionDisplayTitle()`。

M2 的已知限制（v1 范围内不处理，或留待以后）：dev-server 默认没有预置工作区；子代理的事项和独立处理页面只有测试覆盖；Markdown 宽表格的单元格会被截断成「…」；不做开机自启；App 进程被杀后，旧通知里的令牌失效，点开只进入首页。

M2 的实现要点（以后改 App 时要知道）：

- `android/` 的骨架已建立（T2）：Gradle 版本目录、lint（警告即错误）、ktlint、禁止明文流量的配置及其单元测试、CI（`android.yml`）。命令和约定见 `android/AGENTS.md`。
- **开发 App 时连接 dev-server**（`cd plugin && npm run dev-server`，T1 完成）：真实的 `dsh web` + 本插件 + 假模型，消息里的关键词决定模型的行为（ASK 提问、SHELL 审批、STREAM 流式、THINK 思考、SLOW 长输出、FAIL 让这一轮失败）。默认局域网端口 3290、配对页 3291，**不用 3190 / 3191**：用户的开发服务器上，3190 被用户自己的 `dsh web` 服务占用。数据目录 `~/.cache/dshremote-dev` 保存指纹和已配对设备，重启后手机不需要重新配对。`npm run dev-server -- pair` 打印配对二维码，`restart` 重启 DSH，`revoke` 撤销设备。测试代码一律用 `dsh-runtime.mjs` 的 `freePort()` 找空闲端口。
- **证书校验层**在 `android/.../data/net/`（T3）：App 的所有请求都必须用 `ClientFactory.forPin(指纹)` 创建的客户端；手动配对用 `PairingProbe` 取指纹。它的测试放在 `app/src/sharedTest/`，同一组测试在 JVM 和真机上各跑一遍；改动这一层后必须在真机上跑 `connectedDebugAndroidTest`。
- **主机与凭据**在 `android/.../data/host/`（T4）：`HostRepository` 保存主机列表，指纹和 token 用 Keystore 密钥加密（`SecretBox`）；`PairUri` 严格解析配对二维码。持有 token 的类型（`DeviceToken`、`PairUri` 的配对码）的 `toString()` 都不输出秘密。
- **协议客户端**在 `android/.../data/api/`（T5）：`PluginApi` 实现 protocol.md 的全部 REST 接口，失败时抛出 `ApiException`（`Http` / `FingerprintMismatch` / `Network` / `BadResponse`）。发消息用 `OutgoingMessage`，重试时传同一个对象（requestId 不变）。`./gradlew devServerTest` 对 dev-server 跑配对、只读接口、发消息去重和取消配对。
- **实时连接**在 `android/.../data/live/`（T6）：`ConnectionManager` 跟随主机列表，每台主机一条 `HostConnection`；只有存在 `hold()` 时才连接（界面可见、前台服务各持有一个）。连接时先用 `/v1/info` 选地址（lastGoodAddress 优先，失败后其他地址同时试），通过后才用 token 打开 WebSocket。状态 `LinkStatus` 中 `isTerminal` 的几种（需要重新配对、指纹不匹配、App 版本太旧）不会自动重连；指纹不匹配在网络变化后会再试一次。会话详情页用 `HostConnection.subscribe(sessionId)` 收消息，收到 `SessionEvent.Resync` 时重新读取最新一页历史。`devServerTest` 里的 `LiveDevServerTest` 会重启 dev-server 中的 DSH 并撤销自己的测试设备。
- **配对与主机界面**（T7）：`DshRemoteApp` 的 `AppContainer` 手动创建 `HostRepository`、`ConnectionManager`、`PairingService` 并注册 `NetworkMonitor`；`MainActivity` 可见时持有 `hold()`。配对逻辑在 `android/.../data/pairing/PairingService`（同一台电脑再次配对时更新已有主机并撤销旧设备，见 `m2-android.md` 3.2）。界面在 `ui/hosts/`、`ui/pairing/`，用 Navigation Compose 的类型安全路由（`ui/AppNavigation.kt`）；配对码只在 ViewModel 内存中。只有一台主机时首页直接是它的会话列表，有多台时是主机列表；主机管理页从会话列表的菜单进入。
- **会话列表**（T8，`ui/sessions/`）：会话来自实时连接的状态（`hello` 和之后的 `session.*` 事件），不单独请求 `/v1/sessions`。新建会话等 REST 调用用 `ConnectionManager.api(hostId)`：只在在线时可用，地址是连接正在用的地址。会话详情页（`ui/chat/`）在 T8 中是占位页面。
- **会话详情**（T9）：合并规则在 `android/.../data/chat/ChatTimeline`，本地发出的消息在 `Outbox`，界面在 `ui/chat/`（`ChatViewModel` 通过 `ChatBackend` 访问实时连接和接口）。Markdown 用 `multiplatform-markdown-renderer` 0.46.0（m3 + 代码高亮）。设计要点见 `m2-android.md` 3.4「实现要点」。假模型新增 `MARKDOWN` 关键词（输出标题、列表、长代码块、宽表格和链接）。T10 在会话详情页底部加入审批和提问。
- **审批与提问**（T10）：`android/.../data/attention/AnswerForm`（本地校验）、`ui/attention/`（卡片、`AttentionHandler`、独立处理页面 `AttentionRoute`）。会话详情页底部显示这个会话的事项，会话列表上方显示不属于任何会话的事项。T11 的通知点开后进入 `AttentionRoute(hostId, attentionId)`。`devServerTest` 里的 `AttentionDevServerTest` 用第二台测试设备模拟 Desktop 先处理。
- **前台服务与通知**（T11）：`android/.../service/`。`LinkController` 在有主机、通知开关打开并且 App 在前台时启动 `LinkService`，它持有一个 `hold()`，按 `NotificationRules` 发通知和撤回。点通知的入口用进程内的随机令牌（`security.md`「App 端的通知」）。设置页（`ui/settings/`）有通知开关、通知权限、电池优化和小米省电设置的引导。真机上已验证锁屏收到审批通知、点开直接进入处理页面。
- **真机验证**（T12）：清单和结果在 `docs/plans/m2-device-verification.md`，改了连接、通知、前台服务后按它重走相关项目。dev-server 新增 `send`、`decide`、`impostor` 三个子命令（`plugin/AGENTS.md`），用来在真机验证时发消息、模拟 Desktop 处理审批、模拟证书不同的设备。T12 修了一个问题：改地址后连接重建，会话列表在连上之前是空的；现在 `HostConnection` 沿用上一条连接的状态（标为未连接），收到 `hello` 后整体覆盖。
- **查看真机上本 App 的通知**：`adb shell cmd notification list | grep dshremote` 只列出本 App 的通知（tag 中有 hostId 和事项 id）。不要用截图看通知栏，会截到其他 App 的通知。
- **时钟差**：手机和电脑的时钟可能差很多（开发时这台服务器和手机就差了 8 小时），会话的 `updatedAt` 来自电脑。App 用 `/v1/info` 响应的 `Date` 头估算时钟差（`HostLiveState.clockOffsetMillis`），显示「多久以前」时用 `ui/common/ServerClock.kt` 换算。以后显示电脑给的时间都要这样处理。
- **在真机上发消息做验证**：手机上是中文输入法，`adb shell input text` 打的字母会被输入法吞掉，发不出去。需要让某个会话收到消息时，用一台临时测试设备通过插件接口发送（配对、发送、撤销，配对码和 token 只在内存中），或者请用户在手机上输入。不要为此修改手机的输入法设置。
- **测试真机**：用户提供一台 Android 17 的小米手机，通过无线调试连接开发机（用法见 `android/AGENTS.md`「真机」）。手机和开发机在同一局域网，已确认手机能连到 dev-server 的 3290 端口。
- 插件对外的接口以 `docs/protocol.md` 为准，已经按实现定稿，没有待定项。App 只能使用其中列出的接口。
- App 端的安全要求在根目录 `AGENTS.md` 的安全红线和 `android/AGENTS.md` 中，尤其是：证书指纹不匹配时直接拒绝，**不提供「仍然继续」**。

### 插件遗留问题（2026-11-10 已处理）

- **端口被占用时没有提示**：插件现在整体失败——DSH 的插件列表显示 dshremote 为失败，诊断信息和日志写明哪个端口被占用、怎么处理（`src/listen-error.js`），DSH 本身继续运行。和用户确认过：局域网端口失败时不单独启动配对页。`npm run load-check` 在真实的 `dsh web` 上覆盖。Windows Desktop 上的显示还没有看过。
- **首次没有自动打开配对页**：在 Windows 官方 Desktop 上确认 M1 的修复已经生效，不需要改代码（`m1-plugin.md` 第 6 节）。

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
- **cordis（DSH 0.2.2-rc.1 内置 4.1.0）的插件生命周期**，`plugin/index.js` 的写法依赖这两点：
  - 只有 `apply` 本身失败（抛出或返回被拒绝的 Promise），插件才会被标记为失败。`ctx.effect(async …)` 里的错误只会被记进日志，插件仍然显示为 active，所以 `apply` 要 `await ctx.effect(...)`。
  - **`apply` 必须是 `async function`**。普通的 `function` 有 prototype，cordis 把它当构造函数 `new apply(...)` 调用并丢掉返回值；返回的 Promise 被拒绝时成了未处理的拒绝，DSH 的 app-boot 会直接让整个进程退出（`fatal load failure`）。单元测试的假 ctx 测不出这一点，是 load-check 在真实 DSH 上发现的。
  - DSH 本来的策略是：非必需插件启动失败只打印警告，其他插件照常运行；插件列表（`pluginInventory/list`）给出 `fiberPhase: failed` 和 `diagnostic`（错误的 message）。
- **DSH 的多客户端行为**：
  - 每条 `$events` 流都算一个独立的客户端。
  - 一个客户端回答了审批，其他客户端会收到 `cancel`。
  - 已经被处理掉的事件，再回答时 DSH 静默返回成功。所以插件靠 `cancel` 帧来判断 409。
- **Windows 上的实际情况**：
  - 用 VS Code Remote-SSH 时，VS Code 会把服务器上监听的端口自动转发到 Windows 本机，可能占用插件的端口。
  - 装了 VMware、VirtualBox、WSL 的电脑，会有很多虚拟网卡。
  - 防火墙放行时只勾「专用网络」。
- **JVM 测试里的协程泄漏会算到别的测试头上**：用 `Dispatchers.setMain` 的测试，如果视图模型的协程在测试结束后还在跑（或者假对象在协程里抛了没被捕获的异常），kotlinx-coroutines-test 会在下一个 `runTest` 开始时报「There were uncaught exceptions before the test started」，失败的是一个无关的测试，而且是偶发的。用到真实 `HostRepository`（IO 上的 DataStore）的测试，收尾时先 `cancelAndJoin` 视图模型的作用域，再 `resetMain`（见 `HostViewModelsTest`）。查这类问题时把完整套件循环跑到失败为止，再看报告里的 Suppressed 异常。
- **DSH 会把没有完整回复的两条用户消息合在一起发给模型**：上一轮被停止或失败、没有完整的模型回复时，下一轮请求里最后一条用户消息包含两次输入的文字。假模型按关键词分派时，`FAIL` 只看最后一段文字（`test/contract/fake-llm.mjs`）；其他关键词仍按整条消息匹配，在被停止的一轮之后发消息时要注意。
- **DSH 的轮次**：大多数事件的 `data` 都带 `turn`，只有 `user/message` 不带；按 2 条消息分页时，可能有一页以用户消息开头、里面一个带 `turn` 的事件都没有，插件这时往前多读一小段（`facade.messages`）。LLM 返回 400 时 DSH 归为 `INVALID_REQUEST`，不重试，这一轮以 `error` 结束；默认可重试的是 `RATE_LIMIT`、`SERVER` 等，最多 5 次。
- **Compose 的可点区域**：比 48dp 小的可点控件，Compose 的触摸命中会自动把可点区域扩到 48dp，所以新组件的布局按样稿的大小（胶囊 32/34），不为点击区域撑高。Material3 的 `minimumInteractiveComponentSize()` 在当前版本的实测中没有把布局撑到 48dp，不要靠它；要验证点击区域，用 `performTouchInput { click(...) }` 在控件外几 dp 处点（`DesignComponentsUiTest`）。
- **没有放在 `Surface` 或 `Scaffold` 里的 `Text` 默认是黑字**，深色模式下看不见。新页面都要有 `Scaffold` 或 `Surface` 提供内容颜色。
- **真机上已有的测试包**：手机上的 `io.github.qianyeeew.dshremote.debug` 是这台开发机的调试密钥签的，可以直接用默认包名跑真机测试（覆盖安装，数据和配对保留）；`.projecttest.test` 是别的电脑签的，用 `.projecttest` 后缀会安装失败。
- **Compose 聊天列表「停在底部时跟随新消息」**：新的一项插入后，LazyColumn 会把原来的那一项留在原位；消息连续到达时（用户消息和回复几乎同时），不能用那一刻的滚动位置判断「是否在底部」。现在只在用户自己滚动结束后更新「停在底部」，列表变化时据此滚动（`ui/chat/ChatScreen.kt`）。输入框上方的面板用 `weight(1f, fill = false)` 和列表分剩余空间，不要用窗口高度算上限（打开键盘时会把输入框挤扁）。
- **DSH 切换模型时不发出会话事件**：插件原来只在收到 `api-session/*` 事件时推送 `session.updated`（另有 30 秒定时刷新），切换模型后手机要等最多 30 秒才更新。现在切换模型和权限模式成功后插件主动刷新（`LiveHub.refreshSoon()`）。插件依赖 DSH 事件的地方，都要在 devServerTest 或 e2e 里确认事件真的会来。
- **DSH 会把工作目录所在的整个 git 仓库快照到系统临时目录**（`dsh-workspace-changes-*`，每一轮结束时）。开发服务器的 home 目录本身就是一个 git 仓库，dev-server 的工作目录又在 `~/.cache` 下，于是每个会话都复制一遍整个 home 仓库：T5 时把 7.7G 的 `/tmp`（内存盘）写满了，每一轮还要多等约 30 秒才变为空闲。现在 dev-server 把工作目录 `git init` 成独立的空仓库，一轮结束后约 1 秒就空闲。`/tmp` 被写满时先停掉 dev-server，DSH 退出时会清理自己的临时目录。
- **Android 构建和 TLS**：
  - AGP 9 内置了 Kotlin 支持。给 source set 加目录要用 `kotlin.directories.add(...)`；放在 `java.srcDir(...)` 里的 `.kt` 文件不会被编译，测试会**静默地不运行**（构建照样成功）。加测试后要看一眼测试报告里的数量。
  - 手机上的 TLS 实现（Conscrypt）和电脑上的 JDK 有差别。例如握手失败后，Conscrypt 的 session 仍然带着对端证书，JDK 的不带。所以 TLS 相关的测试必须在真机上也跑一遍。
  - MockWebServer 服务器端的 WebSocket 不支持 `cancel()`（会抛 NullPointerException）。测试中要模拟「突然断线」，用 `FakePlugin.ServerSide.kill()`（调用 OkHttp 内部的 `failWebSocket`）。
  - Compose UI Test 默认带的 Espresso 3.5 在 Android 15 以上的系统中调用已经删除的 `InputManager.getInstance`，所有界面测试都会失败。`libs.versions.toml` 中显式指定了 Espresso 3.7.0。界面测试还要求手机**解锁**，否则报 `No compose hierarchies found`。`connectedDebugAndroidTest` 跑完默认会卸载 App，手机上的配对随之丢失（电脑上留下一条用不了的设备记录）：要加 `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`。
  - 小米 Android 17 上，用户关闭本 App 的电池优化后，`specialUse` 前台服务在锁屏 1 小时、深度 Doze（`dumpsys deviceidle` 为 `IDLE`）时仍然在线，审批约 4 秒送达；关掉再打开 Wi-Fi 约 2 秒恢复。无线调试在锁屏后会断开、端口会变，这只影响 adb。
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
