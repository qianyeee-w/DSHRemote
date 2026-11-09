# M2 方案：Android App

- 状态：已确认（2026-11-09）
- 日期：2026-11-09
- 范围：`docs/spec-v1.md` 中「Android App」一节；App 只使用 `docs/protocol.md` 定义的接口
- 完成标志：本文第 6 节的验收清单全部通过，`spec-v1.md` 的验收场景在真机上走通

## 1. 技术选型

见 `android/AGENTS.md`「技术选型」和 ADR 0004、0009、0011。要点：

| 项目 | 选择 |
|---|---|
| 基线 | applicationId `io.github.qianyeeew.dshremote`，minSdk 26，compileSdk 37，targetSdk 35（ADR 0011） |
| 界面 | Compose + Material 3，单 Activity，MVVM |
| 网络 | OkHttp，**每台主机一个客户端，全部由 `data/net/` 的工厂创建** |
| 敏感数据 | DataStore + Keystore 中的 AES-GCM 密钥 |
| Markdown | `multiplatform-markdown-renderer`（纯 Compose） |
| 扫码 | CameraX + ZXing core |
| 后台 | `specialUse` 前台服务 + 本地通知 |

## 2. 目录结构

```
plugin/scripts/dev-server.mjs            # T1：真实插件 + 假模型，给 App 开发用
android/
├── settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew
└── app/src/
    ├── main/java/io/github/qianyeeew/dshremote/
    │   ├── data/net/      # PinnedTls（两道指纹检查）、ClientFactory、PairingProbe、Fingerprint
    │   ├── data/host/     # Host 模型、HostRepository、SecretBox（Keystore AES-GCM）、PairUri 解析
    │   ├── data/api/      # ApiClient：protocol.md 第 4、5、7 节的接口、数据结构和错误
    │   ├── data/live/     # HostConnection（每台主机一条 /v1/stream）、ConnectionManager
    │   ├── service/       # LinkService（前台服务）、Notifier（通知规则）
    │   └── ui/            # hosts/、pairing/、sessions/、chat/、attention/、settings/
    ├── test/              # JVM 单元测试
    ├── devServerTest/     # 对 dev-server 的集成测试（只在本地跑）
    └── androidTest/       # 真机测试：Keystore、关键界面
```

## 3. 关键设计

### 3.1 证书校验（`data/net/`）

按 `security.md`「App 端的证书校验」实现：

- `PinnedTrustManager` 和 `PinnedHostnameVerifier` 都比对 SPKI SHA-256（base64url，无填充）和这台主机保存的指纹。比对用恒定时间。
- 每台主机的 OkHttpClient 由 `ClientFactory.forPin(这台主机的指纹)` 创建：设置上面两道检查，只允许 TLS 1.2 及以上，不使用系统的信任链。
- `res/xml/network_security_config.xml` 禁止明文流量。
- **配对探测** `PairingProbe`：只用于手动输入地址。它的 TrustManager 记录证书后一定抛出异常、中止握手，返回算好的指纹。探测得到的指纹只用来显示给用户核对，不会被直接保存。
- **跨实现一致性**：App 计算的指纹必须和插件 `src/tls/certificate.js` 中 `spkiFingerprint` 的结果完全一致。测试中使用插件生成的证书和插件算出的指纹作为固定数据（`app/src/sharedTest/resources/plugin-certs/`），插件也有测试检查同一份数据。

### 3.2 主机与凭据（`data/host/`）

- `Host`：`hostId`（App 本地生成）、`name`（电脑名，可以重命名）、`addresses`（地址列表，每项为 IP 或主机名加端口）、`lastGoodAddress`、`fingerprint`、`deviceId`、`token`。
- `fingerprint` 和 `token` 用 Keystore 中的 AES-GCM 密钥（不可导出）加密后存进 DataStore；其他字段明文存储。
  - `hostId` 作为附加认证数据参与加密，一台主机的密文挪到另一台主机的记录上无法解密。
  - 密钥不要求用户解锁或验证身份：前台服务在锁屏时也要读取 token 重连。
  - 凭据无法解密时（例如卸载重装后密钥已不存在），主机仍在列表中，但标记为需要重新配对；整个文件损坏时清空列表。
- 持有 token 的类型重写 `toString()`，不输出 token。
- 二维码解析 `PairUri`：严格校验 `v=1`；`h` 为 IPv4；`p` 为 1–65535；`fp` 为 43 个 base64url 字符；`c` 为 8 位配对码字符集；`n` 去掉控制字符并截断长度。任何一项不合法就拒绝整个二维码。
- **地址可编辑**：用户可以添加、修改、删除地址（例如加上 Tailscale 地址）。地址不是信任的依据，修改地址不需要重新配对。
- **删除主机**：先尽量调用 `DELETE /v1/me`（失败也继续，例如电脑不在线），再删除本地数据；界面上说明「电脑不在线时，请到配对页上撤销这台手机」。
- **配对**（T7，`data/pairing/PairingService`）：用二维码中的（或用户核对过的）指纹建立连接 → `/v1/info` 检查协议版本 → `/v1/pair` 发送配对码 → 保存。指纹不对时在 TLS 握手阶段就失败，配对码不会发出；协议版本不认识时也不发送配对码。
  - 配对的电脑和已有主机的指纹相同时（同一台电脑配对了第二次，例如换了地址），更新那台主机而不是新增一台：换成新的 token，把新地址加到地址列表最前面，并用旧 token 调用 `DELETE /v1/me` 撤销旧的设备记录，免得占用设备数量的上限。
  - 从主机管理页「重新配对」时更新指定的主机，可以接受新的指纹（例如插件重新生成了密钥）：用户在电脑旁边扫码或核对过指纹，信任的依据和第一次配对相同。
  - 配对成功但本地保存失败时，立即用新 token 撤销刚配对的设备。
  - 配对码只保存在 ViewModel 的内存中，不放进导航参数和 SavedState；手动输入配对码的输入框使用密码类型的键盘（输入法不记录），文字照常显示。

### 3.3 实时连接（`data/live/`）

- `ConnectionManager` 位于 Application 作用域，**每台主机一条** `/v1/stream` 连接。界面和前台服务共用这条连接，不各开一条。
- **地址选择**：连接时先试 `lastGoodAddress`；失败后同时尝试其他地址，第一个通过指纹校验并完成握手的地址获胜，并记为 `lastGoodAddress`。
  - 实现（T6）：竞速用无需认证的 `/v1/info`，选出地址后才用 token 打开 WebSocket。这样 token 只发给通过了指纹校验的地址，竞速也不会占用插件「每台设备 4 条连接」的名额。`/v1/info` 同时用来检查协议版本和 DSH 兼容性。
- **重连**：网络变化（`ConnectivityManager.NetworkCallback`）时立即重连；其他断线按指数退避（1 秒起，最长 30 秒，带随机抖动）。收到 `429` 和 `1013` 时按退避重连。
- **停止重连**：握手 `401` 或关闭码 `4401` 时，主机标记为「需要重新配对」，不再重连，并提示用户。关闭码 `1008` 记为 App 的 bug（日志中不带任何凭据）。
  - 指纹不匹配也停止重连。但网络变化后会再试一次：换了网络后，同一个地址可能指向另一台设备（例如家里电脑的局域网 IP 在别的网络里是别的设备），回到原来的网络后应能自动恢复。每次都在 TLS 握手时就拒绝，不发出任何请求。
  - 协议版本不认识时停止（需要升级 App）；DSH 版本不兼容时按最长的退避间隔（30 秒）继续探测，因为 DSH 可能会被升级。
- **心跳**：App 每 30 秒主动发 ping（OkHttp 的 `pingInterval`），在网络已经断了却没有报错时（例如切换 Wi-Fi）也能发现连接已死。握手后 20 秒内没有收到 `hello` 时断开重连。
- **启停**：`ConnectionManager.hold()` 按引用计数决定是否连接：界面可见时、前台服务运行时各持有一个，全部释放后断开所有连接（3.6）。
- **状态**：`hello` 到达时**整体覆盖**这台主机的会话和待处理事项；之后按 `eventId` 去重，按事件增量更新。
- **订阅**：会话详情页打开时订阅、离开时取消订阅，按引用计数管理。重连后重新订阅，并重新读取最新一页历史补齐消息。前台服务不订阅任何会话。
- **在线状态**：主机列表显示「在线 / 连接中 / 离线 / 需要重新配对 / DSH 版本不兼容 / 需要升级 App」。离线时用无需认证的 `/v1/info` 探测，同样经过指纹校验。

### 3.4 消息与流式输出

- 历史从最新一页开始，向上滚动时用 `cursor` 取更早的一页；是否还有更早的消息只看 `hasMore`。
- `message.upsert`：同一 `id` 只接受更大的 `rev`，用新内容整条替换；带 `replaces` 时删除对应的草稿；`message.removed` 删除草稿。
- 发送消息：每条消息生成一个 UUID 作为 `requestId`，网络重试时沿用原来的 `requestId`。界面上显示「发送中 / 已发送 / 失败，点此重试」。运行中发送的消息默认为 `steer`（插话），也可以选 `queue`。
- **Markdown 体验**：
  - 流式输出时不闪烁：只重新渲染变化的那条消息，草稿的更新频率限制在每帧一次以内。
  - 可以长按选中和复制文字，消息也有「复制全文」操作。
  - 代码块可以横向滚动，有语法高亮和复制按钮；表格可以横向滚动。
  - 点击链接时先确认，再用系统浏览器打开。
- 工具卡片和思考过程默认折叠。工具卡片标题显示 `summary` 和状态图标，展开后显示 `input` 和 `output`（被截断时注明）。
- **实现要点（T9）**：
  - 合并规则在 `data/chat/ChatTimeline`（纯逻辑）。插件重启后 `rev` 从 1 重新开始，草稿被替换或删除后插件也删掉了它的 `rev` 记录，所以每次 `Resync` 时清空 rev 记录，草稿被替换或删除时也删掉它的记录。重新读取最新一页期间收到的推送先暂存，读到后再按顺序重放。
  - 替换了草稿的正式消息沿用草稿的列表 key，配合 Markdown 库的 `retainState`，流式输出结束时不会闪一下。
  - 发出的消息在 `data/chat/Outbox` 中显示到它出现在会话日志里为止，按文字匹配（同样的文字按先后顺序对应）。读到第一页历史之前不能发送。
  - 「停止」在运行中和等待处理时都显示；「插话」开关只在运行中显示。
  - 停在底部时自动跟随新消息；往上翻看历史时不打扰。
  - Markdown 的标题字号调小（库默认用 display 字号）；Android 13 起复制后由系统提示，App 不再重复提示。

### 3.5 审批与提问

- 会话详情页底部显示这个会话的待处理事项；另有一个独立的处理页面（通过 `attentionId` 打开），用于子代理发起、`sessionId` 不在会话列表中的事项，以及从通知直接进入的情况。
- 审批只有「允许」和「拒绝」；提问支持单选、多选和自由文本，按 `protocol.md` 5.3 的规则在本地先做校验。
- 收到 `409 ATTENTION_RESOLVED` 或 `attention.resolved` 时收起界面。`by: "other"` 时提示「已在其他地方处理」。
- **实现要点（T10）**：
  - 回答前的本地校验在 `data/attention/AnswerForm`，规则和插件 `src/dsh/attention.js` 的 `toDshAnswer` 一致（至少回答一个问题；单选最多一项；只能选已有选项；自由文本最多 4000 个字符，只有空白时不发送）。
  - `ui/attention/AttentionHandler` 管理草稿、提交和结果，会话详情页和独立页面共用。`200` 表示这台手机的回答生效；`409` 当作「在别处处理」；`404` 当作已经不存在；网络错误可以重试。
  - 会话详情页显示 `sessionId` 是这个会话的事项，以及会话摘要的 `attention` 中列出的事项；会话列表上方单独显示不属于任何会话的事项（例如子代理发起的），点击进入独立页面。
  - 事项卡片在输入框上方，和消息列表按权重分剩余空间（最多一半），打开键盘时不会挤扁输入框。

### 3.6 前台服务与通知（`service/`）

- **服务的启停**：有已配对的主机并且通知开关打开时，用户打开 App 后启动前台服务（`specialUse`）；没有主机或者通知关闭时停止。通知关闭时，App 只在界面可见时保持连接。
- **常驻通知**：低优先级，显示「已连接 N 台电脑」。
- **通知渠道**：
  - 「需要你处理」：高优先级，用于审批和提问。同一个 `attentionId` 只通知一次，重连后 `hello` 里重复出现的事项不重复通知；收到 `attention.resolved` 时撤回通知。
  - 「任务完成」：默认优先级。收到 `session.idle` 时发出（插件已经做了 8 秒防抖）；用户正在看这个会话时不发。
- **点击通知**：审批或提问打开处理页面；任务完成打开会话详情页。通知里不包含可以被其他 App 伪造调用的入口（使用 `PendingIntent.FLAG_IMMUTABLE` 的显式 Intent）。
- **权限与引导**：Android 13 及以上首次启动时申请通知权限；引导用户把 App 加入电池优化白名单，并提示小米等系统的「自启动」和「省电策略：无限制」设置。
- **实现要点（T11）**：
  - 通知规则在 `service/NotificationRules`（纯逻辑）：按实时状态决定发什么，所以断线期间出现、重连后才在 `hello` 里看到的事项也会通知；同一个事项只通知一次（用户划掉后也不再发）；收到 `attention.resolved`、在线状态里没有了、或者主机被删除时撤回；断线期间状态可能过时，不据此撤回。用户正在看的会话不发通知（包括审批和提问），打开会话时撤回它的通知。
  - 点通知的入口：Intent 中只有一个随机令牌，对应的页面只记在进程内存里，见 `security.md`「App 端的通知」。
  - 通知权限在有了第一台电脑之后申请一次（没有电脑时通知没有意义），之后在设置页里可以再申请或打开系统的通知设置。
  - 电池优化只打开系统的设置列表，由用户自己选择（不申请 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限）；首页显示可以关闭的提示，设置页里有同样的引导和小米的「自启动」「省电策略」说明。
  - 设置页从首页右上角的菜单进入。

### 3.7 界面导航

```
主机列表（首页）── 扫码配对 / 手动配对 → 指纹核对 → 配对结果
   └── 主机 → 会话列表（waiting → running → idle）── 新建会话（选择工作区）
                 └── 会话详情（消息、输入框、停止、模型和权限模式、待处理事项）
设置：通知开关、主机管理（重命名、编辑地址、删除、重新配对）、电池优化引导
```

只有一台主机时，首页直接显示它的会话列表。

点主机列表的一行进入它的会话列表；主机管理页从会话列表的菜单进入（T8）。

会话列表的内容来自实时连接（`hello` 和 `session.*` 事件），不在线时显示上次连接时的内容并注明。新建会话时先读取 `/v1/workspaces` 让用户选择，也可以不指定工作区。会话的时间来自电脑的时钟，按 `/v1/info` 的 `Date` 头估算的时钟差换算后再显示「多久以前」。

## 4. 测试方法

| 层次 | 怎么测 | 在哪里跑 |
|---|---|---|
| 证书校验、二维码解析、协议数据结构、重连状态机、通知规则、ViewModel | JVM 单元测试。网络部分用 MockWebServer 加 `okhttp-tls` 生成的自签证书 | 本机 + CI |
| 和真实插件联调 | `devServerTest`：JVM 测试连接 T1 的 dev-server，走配对、会话、流式、审批、提问、撤销 | 本机 |
| Keystore、关键界面 | `androidTest`，在真机上跑（Compose UI Test） | 本机 + 真机 |
| 通知、锁屏、扫码、重启 DSH、换网络 | 按清单手动验证（T12 写成 `docs/plans/m2-device-verification.md`） | 真机 |

安全相关的**反向用例**（必须有）：

- 指纹错误时 TrustManager 拒绝；指纹错误时 HostnameVerifier 单独也拒绝；没有证书时拒绝；明文 HTTP 被拒绝。
- 配对探测的连接上服务器收不到任何 HTTP 请求。
- 指纹不匹配时界面上**没有**「继续」之类的选项（界面测试断言）。
- 握手 `401` 或关闭码 `4401` 后不再重连。
- token 不出现在日志、`toString()` 和错误信息中。
- 非法的二维码（错误的 `v`、指纹长度不对、多余或缺少字段）被整体拒绝。

## 5. 任务拆分（按顺序，每项单独提交，做完一项汇报一次）

| # | 任务 | 主要内容 | 验收 / 测试 |
|---|---|---|---|
| T1 | 插件 dev-server | `npm run dev-server -- [--port N] [--home DIR]`：启动假模型和带插件的 `dsh web`。局域网端口默认 3290，不用 3190 / 3191；配对页端口默认为局域网端口 + 1。DSH_HOME 持久化（默认在仓库外的 `~/.cache/dshremote-dev`），重启后指纹和已配对设备保留。终端打印地址、指纹、假模型关键词说明；按 `p` 生成配对码并打印终端二维码，按 `r` 重启 DSH（模拟换端口）。只复用测试部件，不改插件的 `src/` | 在本机启动后，用 `pinned-client.js` 完成配对和一次 STREAM 对话；手机通过局域网能访问 `/v1/info` |
| T2 | Android 骨架 + CI | Gradle 工程、版本目录、wrapper、空的 Compose 页面、ktlint、lint、`network_security_config`、strings.xml；新增 `.github/workflows/android.yml`（ubuntu：test、lint、ktlintCheck、assembleDebug） | CI 通过；debug APK 能装到真机上并启动 |
| T3 | 证书校验层 | `data/net/`：指纹计算、两道检查、ClientFactory、PairingProbe | 第 4 节全部网络相关的反向用例；跨实现指纹一致性测试。**合并前请你逐行审查 diff** |
| T4 | 主机与凭据存储 | `data/host/`：Host 模型、SecretBox、HostRepository、PairUri 解析 | 二维码解析的正反用例（JVM）；Keystore 加解密、重装后无法解密（真机）；token 不出现在 `toString()` 中 |
| T5 | 协议客户端 | `data/api/`：第 4 节全部接口、数据结构、错误码映射、`requestId` 重试语义 | 用 JSON 固定数据做单元测试；`devServerTest` 跑通全部只读接口和配对 |
| T6 | 实时连接 | `data/live/`：每台主机一条连接、多地址竞速、退避、网络变化、关闭码处理、hello 覆盖、eventId 去重、订阅引用计数 | MockWebServer 模拟各种关闭码和断线；`devServerTest`：重启 DSH 后自动恢复、撤销后停止重连 |
| T7 | 配对与主机界面 | 主机列表（在线状态）、扫码、手动输入和指纹核对、配对时各种错误的提示、主机管理（重命名、编辑地址、删除、重新配对） | ViewModel 单元测试；界面测试：指纹不匹配时没有「继续」；真机扫码 dev-server 的二维码完成配对 |
| T8 | 会话列表与新建会话 | 排序、状态标签、工作区选择、空状态 | ViewModel 单元测试；真机：dev-server 中新建会话后出现在列表里 |
| T9 | 会话详情 | 历史分页、流式草稿、工具卡片、思考过程、Markdown、发消息（queue / steer）、停止、切换模型和权限模式 | 消息合并逻辑（rev、replaces、removed）的单元测试；真机：STREAM 输出不闪烁，长代码块和表格可以滚动和复制 |
| T10 | 审批与提问 | 会话内和独立的处理页面、允许 / 拒绝、单选 / 多选 / 文本、409 和 resolved 时收起 | 单元测试：回答前的本地校验；`devServerTest`：SHELL 审批和 ASK 提问；在 Desktop 处理后手机自动收起 |
| T11 | 前台服务与通知 | LinkService、通知渠道、去重与撤回、任务完成通知、点通知跳转、通知权限、电池优化和自启动引导、通知开关 | 通知规则的单元测试；真机：锁屏后收到审批通知，点开直接进入处理页面 |
| T12 | 收尾 | 写真机验证清单 `m2-device-verification.md`，按 `spec-v1.md` 的验收场景走一遍，修复发现的问题，更新 `status.md` 和相关文档 | 第 6 节验收清单 |

T3、T4 涉及安全红线，提交前请你逐行审查 diff（`security.md` 的要求）。

## 6. M2 验收清单

- [ ] CI（android.yml）通过：单元测试、lint、ktlint、assembleDebug
- [ ] 真机扫码配对成功；手动输入地址时显示指纹前 8 位，核对后配对成功
- [ ] 指纹不匹配时直接拒绝，提示重新配对，没有「继续」选项
- [ ] 主机列表正确显示多台主机的在线状态；修改地址后不需要重新配对
- [ ] 会话列表排序正确；新建会话可以选择工作区
- [ ] 会话详情：流式输出、Markdown、工具卡片和思考过程默认折叠、历史分页
- [ ] 发消息（含运行中插话）、停止、切换模型和权限模式
- [ ] 审批允许 / 拒绝、提问单选 / 多选 / 文本；在 Desktop 处理后手机自动收起
- [ ] 锁屏状态下收到审批通知，点开直接处理；任务完成通知；相同事件不重复通知
- [ ] 重启 DSH（端口变化）后自动重连，不需要重新配对；切换 Wi-Fi 后自动恢复
- [ ] 在配对页撤销这台手机后，手机立即无法连接，并提示重新配对
- [ ] 第 4 节的反向用例全部有测试并通过
- [ ] `spec-v1.md` 的验收场景 1–7 在真机上走通（电脑端用 Windows 官方 Desktop 或 Linux 的 `dsh web`）

## 7. 不在 M2 范围内

- spec「第二版」和「以后再说」中的全部功能，包括在通知里直接审批、mDNS、图片附件、中英文界面、平板和横屏、主题。
- 用系统相机扫码直接拉起 App（外部 `dshremote://` 链接）：v1 只在 App 内扫码。
- 开机自动启动前台服务。
- release 签名和发布渠道（GitHub Releases 等），M2 之后单独讨论。
- 插件遗留问题（端口被占用时没有提示、首次没有自动打开配对页），单独修复。

## 8. 风险

| 风险 | 应对 |
|---|---|
| 小米等系统的省电策略杀掉前台服务，锁屏后收不到通知 | T11 做引导；T12 在真机上测试长时间锁屏的效果，把结论写进文档 |
| Markdown 库在长消息和流式输出下性能不够 | T9 先用真实的长输出测试；不行时把长消息分段渲染，或者换库（换库需要写 ADR） |
| 以后升级 targetSdk 到 37 需要本地网络权限 | 已记录在 ADR 0010、0011 |
| 真机的无线调试连接不稳定（锁屏或换网络后断开） | 自动化测试以 JVM 为主；需要真机时请用户重新打开无线调试 |
