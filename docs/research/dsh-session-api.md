# DSH 0.2.2-rc.1 会话接口调研

- 日期：2026-11-08
- 对象：`@deepseek-ai/dsh@0.2.2-rc.1`（git 标签 `dsh-v0.2.2-rc.1`）
- 结论：见第 6 节和 [ADR 0007](../decisions/0007-plugin-facade-api.md)

## 1. 资料来源与验证方法

| 来源 | 许可证 | 用法 |
|---|---|---|
| DSH 官方仓库：`docs/api-gateway.md`、`packages/api/*/README`、`packages/client/connection/README`，以及源码中的 `@Remote` 标注 | MIT | **主要依据** |
| dsh-Go 的 `packages/dsh_client`（Dart） | **AGPL-3.0** | 只用来交叉核对接口名，**不复制任何代码** |

验证方法：在 Linux 上运行真实的 `dsh web --port 0`（模拟 Desktop 的随机端口），用一个假的 Anthropic Messages 模型服务驱动 Agent 调用工具，再由探测脚本模拟手机客户端，逐项调用接口。脚本和结果在 [`probe-0.2.2-rc.1/`](probe-0.2.2-rc.1/) 目录，复现方法见第 8 节。

**25 项检查全部通过**（`results.json`）。

## 2. 认证

| 项目 | 结论 | 验证 |
|---|---|---|
| 启动 token 换 cookie | `GET /?token=<启动token>` 返回 303，并设置签名 cookie | ✅ |
| 未带 cookie 的调用 | 返回 401 | ✅ |
| Host 校验 | Host 必须是回环地址（或在 `trustedHosts` 中）。插件转发时把 Host 改写为回环地址即可通过，这一点已在 dsh-tailnet-remote 中验证 | ✅ |
| 插件获取启动 token | `ctx.connection.authenticatedUrl(baseUrl)`（公开接口） | ✅ |

## 3. 传输格式

### 3.1 一元调用（HTTP）

```
POST /api/<namespace>/<method>
Content-Type: application/json
Cookie: <DSH cookie>

{ "type": "client-request", "rpcId": "<uuid>", "method": "<namespace>/<method>", "payload": { "args": { ... } } }
```

响应：`{ "type": "server-response", "rpcId": "...", "result": { "ok": true, "value": ... } }`，失败时为 `{ "ok": false, "error": { "code", "message", "details" } }`。

> ⚠️ **坑：`args` 的字段名就是 Host 方法的参数名。**例如 `session/list(_request, signal)` 必须传 `{ "_request": {} }`，`session/create(request)` 必须传 `{ "request": {...} }`。官方只要改一下参数名，wire 格式就会变，而且文档里没有单独说明这一点。参数不符时返回 `gateway/arguments-invalid`，错误信息里会写出缺少的字段名。

### 3.2 流（WebSocket mux）

- 地址：`/api/remote.mux`，握手时带 cookie。一条连接上同时承载多条逻辑流。
- 客户端发送：`{type:"open", streamId, endpoint, payload:{args}}`、`{type:"item", streamId, value}`（上行）、`{type:"end", streamId}`、`{type:"cancel", streamId}`
- 服务端返回：`{type:"item", streamId, value}`、`{type:"end", streamId}`、`{type:"error", streamId, error}`
- 心跳：Host 每 2 秒发送一次 WebSocket Ping；连续两个心跳周期内没有任何活动，就会断开连接。

### 3.3 应用事件流 `$events`

- 在 mux 上 `open` 端点 `$events`，`args` 为 `{}`。第一帧是 `{type:"ready", clientId, host:{home}}`。
- 广播事件：`{type:"emit", event, args:[...]}`
- **可回答的事件（waterfall）**：`{type:"waterfall", event, eventId, agentId, request}`。回答方式：调用一元接口 `$events/result`，`args` 为 `{clientId, eventId, outcome}`，其中 outcome 为 `{kind:"result", value}`、`{kind:"next"}` 或 `{kind:"rejected", error}`。
- 撤回：`{type:"cancel", eventId}`。由别的客户端处理了，或请求被中止时，会收到这一帧。

## 4. v1 功能对应的接口

✅ = 已在 0.2.2-rc.1 上实测通过；📄 = 源码中存在但没有单独实测；❓ = 还没有确定具体接口

| v1 功能 | 接口 | 状态 | 说明 |
|---|---|---|---|
| 会话列表 | `session/list` `{_request:{}}` | ✅ | 每项包括 `sessionId`、`running`、`updatedAt`、`cwd`、`blank`，以及 projections 摘要 |
| 会话状态变化 | `$events` 中的 `api-session/status` `[sessionId, running]` | ✅ | 可用于判断「任务完成」：running 从 true 变为 false |
| 新增、删除会话 | `api-session/added`、`api-session/removed` | ✅ / 📄 | |
| 会话活动 | `api-session/activity` `[sessionId, time]` | ✅ | |
| 工作区 | `workspace/follow`（流） | ✅ | 先返回一帧 baseline，之后是增量 |
| 新建会话 | `session/create` `{request:{workspaceId?, cwd?}}` | ✅ | |
| 实时跟随会话 | `session/follow` `{request:{address, activate, assistantStream, maxMessages}}`（流） | ✅ | 先返回 snapshot，之后是**原始会话事件**和 `assistant-stream` 增量块，见 5.2 |
| 会话运行状态 | `session/observeState`（流） | ✅ | `{cursor, active, running, queue, pendingInput}` |
| 历史分页 | `session/page` `{request:{address, maxMessages, beforeSeq}}` | ✅ | 返回 `{cursor, records, hasMore}`，用 `beforeSeq` 向前翻页 |
| 发消息 | `session/prompt` `{request:{requestId, sessionId, mode:"queue", content}}` | ✅ | |
| 插话 | `session/prompt` 的 `mode:"steer"` | 📄 | |
| 停止任务 | `session/cancel` `{request:{sessionId}}` | ✅ | |
| 审批 | `approval/request` waterfall → `$events/result`，value 为 `"allowed-once"` 或 `"rejected"` | ✅ | 返回的 request 中包括 `toolName`、`callId`、`reason`，以及多语言的 `displayReason` |
| 回答提问 | `user-questions/request` waterfall → `$events/result`，value 为 `{answers:[{id, selected:[...], custom?}]}` | ✅ | 另有一元接口 `userQuestions/answer`（📄），可以用来回答已经进入 `continued` 状态的问题 |
| 模型列表、切换模型 | `session/modelCatalog` ✅、`session/selectModel` 📄 | ✅ / 📄 | |
| 权限模式 | `permissionPresets/catalog` | ✅ | 选项为 `read-only`、`workspace-write`、`danger-full-access`。**为单个会话切换权限模式的接口 ❓**，M1 中继续确认 |

## 5. 关键发现

### 5.1 多个客户端可以同时在线 ✅

两个客户端同时打开 `$events` 时，**都会收到同一个审批请求**（`eventId` 相同）。其中一个回答以后，**另一个会收到 `cancel` 帧**。所以手机和电脑上的 Desktop 窗口可以同时在线：在任何一边处理都可以，另一边会自动收起这个请求。这正好就是 protocol.md 中 `attention.resolved` 需要的信号。

### 5.2 `session/follow` 返回的是原始内部事件

一次普通对话产生的事件类型包括：`turn/start`、`step/start`、`system/message`、`user/message`、`request/header`、`request/context`、`assistant-stream`（增量块，带 revision 和 index）、`assistant/message`、`session/title`、`session-log-deepseek/delivery-accepted`、`step/end`、`turn/end` 等。

其中不少是内部事件或遥测事件。App 如果直接使用这个流，就必须自己实现一个「事件 → 聊天消息」的转换器（dsh_client 里的 `ChatTranscript` 做的就是这件事），而且会直接依赖 DSH 内部的事件格式。会话头里的 `version: 4` 也说明这套格式一直在演进。

### 5.3 审批只有「允许一次 / 拒绝」

`ApprovalOutcome` 只有 `allowed-once`、`rejected`、`cancelled`、`unavailable` 四种值。DSH 的设计是**每次批准只对当前这个请求有效**。想让 Agent 少弹审批，要通过切换权限模式（permission preset）来实现，不是在审批时选「以后都允许」。

> 这修正了之前的判断：社区 App 只提供「允许一次 / 拒绝」并不是它的局限，DSH 本身就只有这两种决定。`spec-v1.md` 已同步修改。

### 5.4 Peer 权限不能用于 v1 的手机接入

DSH 的 `remote-access` 包和 Peer 身份机制（2026-10-12 实现）目前用于 liveshare 的只读分享。**非 owner 的 Peer 收不到可回答的 waterfall 事件**，它调用 `$events/result` 会以 `gateway/invocation-unavailable` 失败。也就是说，非 owner 的 Peer 不能处理审批和提问，所以 v1 的插件必须以 owner 身份访问 DSH。

以后如果 DSH 允许非 owner 的 Peer 回答事件，可以考虑为手机签发一个权限受限的身份。这一点记入 roadmap。

### 5.5 发版节奏

0.2.1-rc.1（2026-10-16）到 0.2.2-rc.1（2026-11-04）之间，发布了 rc.1 到 rc.5，以及 0.2.2 的多个 alpha 版本。官方在 `docs/upgrade-guide/<版本号>/` 中为不兼容的改动写了迁移说明。

### 5.6 T7 实现外观层只读接口时确认的细节

- **工作区和会话的关系**：`session/list` 的结果里没有工作区信息。`workspace/follow` 首帧（baseline）中每个工作区带 `sessionIds`，还有 `archivedSessionIds`、`pinnedSessionIds`。插件读完首帧就关闭这条流。
- **会话标题**在 `projections.values.title`，还没有标题时为 `null`。
- **`session/page` 的切页规则**（`api/session-controller/src/history.ts` 的 `paginate`）：从 `beforeSeq`（不含）向前数，只把 `user/message` 和 `assistant/message` 计入 `maxMessages`，返回 `[切点, beforeSeq)` 之间的全部事件，`hasMore` 表示切点之前还有事件。会话日志的 seq 从 0 开始连续编号，所以下一页的 `beforeSeq` 就是这一页第一条记录的 seq。
  - 切点可能落在一次工具调用中间：这一页开头是 `tool/call`、`tool/result`，它们所属的 `assistant/message` 在更早的一页。插件把开头这些事件去掉，把游标设在第一个 `turn/start`、`user/message` 或 `assistant/message` 上（`transcript.js` 的 `convertPage`），并用单元测试验证了「切在任何位置，两页拼起来都和整段转换一致」。
- **DSH 注入的用户消息**：每轮开始时 DSH 会追加 `source.kind` 为 `runtime-context`、`time-context` 的 `user/message`，它们也计入 `maxMessages`。只有 `source.kind` 为 `user` 的才是用户输入。
- **思考内容**是 `assistant/message` 中 `type: "reasoning"` 的块。工具调用是 `type: "tool-call"` 的块（`arguments` 是 JSON 字符串），结果在 `tool/result`（`message.toolCallId`、`isError`）。
- **审批被拒绝**：`approval/asked { id, callId }` → `approval/decided { id, outcome: "rejected" }`，随后 `tool/result` 的 `isError` 为 `true`。
- **被停止的一轮**：`turn/end` 的 `reason.kind` 为 `aborted`。
- 不存在的会话：`session/page` 返回错误码 `session/not-found`。
- Windows 上的命令工具叫 `pwsh`，参数和 `bash` 相同（`command`、`description`、`sandbox_permissions`、`justification`）。

### 5.7 T8 实现写操作时确认的细节

- **多客户端与 waterfall**（`api/gateway/src/index.ts`）：
  - 新订阅 `$events` 的 owner 客户端，会在 `ready` 帧之后立即收到所有还没处理的 waterfall（重放）。所以插件断线重连后能恢复待处理事项。
  - 任一客户端回答后，DSH 从其他客户端撤回这个事件，并向它们发送 `cancel` 帧；回答的客户端自己**不会**收到 cancel。任务被停止时，所有客户端都收到 cancel。两种情况无法区分。
  - **已经被处理掉的事件，再用 `$events/result` 回答，DSH 静默忽略并返回成功**（源码注释：idempotent no-op）。所以「别处已处理」只能通过 cancel 帧判断。插件的做法：回答成功后再等约 0.3 秒，期间收到 cancel 就返回 409。契约测试中用另一个客户端模拟 Desktop，验证了「Desktop 先处理 → 手机 409」「手机先处理 → 手机 200」两种情况，结果和工具的实际状态一致。
  - 用不存在的 `clientId` 回答，返回 `gateway/internal`。
- **`requestId` 去重**：源码中 `hasPromptRequest` 会检查 inbox 和会话历史，但在 0.2.2-rc.1 上实测，同一个 `requestId` 连续发两次，两条都会写进会话。所以去重由插件来做。
- **切换权限模式**：没有单独的接口。Web 界面（`client/ui-permission-presets`）执行 `/permission <preset>` 命令，对应一元接口 `commands/execute`，`args` 为 `{ agentId: <sessionId>, line, submittedAttachments: [] }`（注意是 `agentId`，不是源码参数名 `agent`）。成功时返回 `{ commandId, result: { kind: "success", text } }`；模式名不对时同样返回 `ok`，但 `result.kind` 为 `error`。会话的当前模式在 `projections.values.permissions.currentValue`。
- **切换模型**：`session/selectModel { request: { sessionId, provider, model } }` → `{ selected: { provider, model } }`。模型不存在时错误码为 `session/model-unavailable`。会话的模型在 `projections.values.modelSelection`（`next`、`lastUsed`）。
- **错误码**：不存在的会话 `session/not-found`（prompt、cancel、selectModel、commands/execute 都是）；不存在的工作区 `workspace/not-found`；空消息 `gateway/bad-request`；停止一个空闲的会话返回成功。
- 审批请求中只有 `toolName`、`callId`、`reason`、`displayReason`（8 种语言），**没有要执行的命令**。插件用 `callId` 在会话日志末尾找到 `tool/call`，补上命令。
- `session/page` 在源码中已标为 `@deprecated`，建议改用 `events`。0.2.2-rc.1 上仍然可用，以后的版本适配时留意。

### 5.8 T9 实现实时流时确认的细节

- **会话列表的广播**（`api/session-controller/src/index.ts`）：
  - `api-session/added`：会话出现，或它的 Agent 被创建、销毁时发出，附带完整的会话摘要。
  - `api-session/removed`：会话**从内存中卸载**时发出（`session/disposed`），并不代表被删除。
  - `api-session/status [sessionId, running]`：Agent 开始、停止运行。
  - `api-session/activity [sessionId, updatedAt]`：用户发了消息。
  - **标题变化没有广播**。所以插件的会话摘要变化一律通过重新读取 `session/list` 比较得到（有事件时防抖 0.3 秒，有手机在线时另外每 30 秒刷新一次）。
- **`assistant-stream` 帧**（`types.ts` 的 `SessionAssistantStreamFrame`，`session/follow` 时带 `assistantStream: true`）：
  - `start { attemptId, turn, step, startedAfterSeq }` → 多个 `chunk { chunk }` → `end { outcome }`。
  - chunk 的类型：`block-start { index, blockType }`、`text-delta`、`reasoning-delta`（`{ index, text }`）、`tool-call-delta { index, id, name?, argumentsDelta }`、`block-end { index, block }`、`usage`、`finish`。
  - `end.outcome`：`{ kind: "committed", eventType: "assistant/message" | "assistant/attempt", seq }` 或 `{ kind: "abandoned" }`。实测**写入日志的 `assistant/message` 事件先于 `end` 帧到达**，两者通过 `turn`、`step` 对应。
  - 订阅时如果正在输出，snapshot 的 `assistantStream.activeAttempt` 中带有已经输出的部分（`stream` 数组，元素为 `{ type: "chunk", chunk }` 或压缩过的 `{ type: "text-chunks", index, texts }`）。
- **一个客户端只能有一条 `$events` 流**：DSH 把每条 `$events` 流当成一个独立的客户端。插件曾因为同时注册两个监听者而打开了两条流，结果用其中一条回答提问后，另一条收到 `cancel`，被误判为「别处已处理」（端到端测试发现，已修复并加了回归测试）。
- **`ask_user_question` 的限时模式**（`interaction/tool-ask-user` 的 `mode: "timed"`，默认是 `legacy`）：没有客户端通过 `userQuestions/attachWait` 认领时，超时后提问变为 `continued` 状态并撤回 waterfall，之后只能用 `userQuestions/answer` 回答。v1 只支持默认的阻塞模式，限时模式记入 roadmap。

## 6. 结论：透传还是外观层

| 考量 | 透传（App 直接说 DSH 协议） | 外观层（插件提供精简接口） |
|---|---|---|
| App 端复杂度 | 高：要实现 mux、`$events`、waterfall，以及原始事件到聊天消息的转换 | 低：只处理精简后的数据 |
| DSH 升级的影响 | 参数名、事件格式一变，就要重新发布 App | 只需要改插件的 `src/dsh/` |
| 插件复杂度 | 低 | 中：插件里要做事件转换，以及审批和提问的中转 |
| 和 roadmap 中「自动适配」预留设计的契合程度 | 差：App 直接依赖 DSH 的格式 | 好：所有改动都集中在适配层 |
| 移动端流量 | 内部事件和遥测事件也会发到手机 | 只发送需要的数据 |

**结论：v1 采用外观层**，推翻 protocol.md 草案中「倾向透传」的初步判断。主要理由有三条：

1. 5.2 中的原始事件流和 3.1 中参数名即 wire 格式这个坑，让「App 直接依赖 DSH」变得很脆弱。
2. DSH 发版很频繁。
3. roadmap 中预留的设计要求 App 不依赖 DSH 的数据格式。

插件内部仍然通过官方的 Remote API（HTTP 加 mux，以 owner cookie 访问）与 DSH 通信。全部的转换逻辑都放在 `plugin/src/dsh/`。

## 7. 留到 M1 实现时确认的问题

- 为单个会话切换权限模式的接口（4 节中标 ❓ 的那一项）。
- `session/selectModel` 和 `mode:"steer"` 的实测。
- `assistant-stream` 增量块的完整语义：revision、index、block-start / delta / block-end，以及重连后如何续接。插件的事件转换器依赖这一点。
- 图片附件（v2）：`session/prompt` 的 image 内容块和 `session/attachment`。
- ~~插件是通过本机 HTTP 加 mux 调用 DSH，还是在进程内通过 `connection.createPeer()` 直接调用。~~ **已确定（T6）：本机 HTTP + mux。** 这和浏览器、Desktop 窗口走的是同一条路径，调研时已经逐项实测过；进程内调用要依赖 Peer 的内部类型，DSH 升级时更容易受影响。实现见 `plugin/src/dsh/connection.js`。

## 8. 复现

```sh
# 1. 假模型服务
node docs/research/probe-0.2.2-rc.1/fake-llm.mjs &

# 2. 用独立的 DSH_HOME 启动 dsh web，并让它连接假模型
export DSH_HOME=$(mktemp -d) DEEPSEEK_BASE_URL=http://127.0.0.1:9911 DEEPSEEK_API_KEY=sk-0123456789abcdef0123456789abcdef
mkdir -p /tmp/probe-ws && cd /tmp/probe-ws
dsh web --no-open --port 0          # 记下输出的带 token 的链接

# 3. 探测（需要先 npm i ws@8）
PROBE_WORKDIR=/tmp/probe-ws node <仓库>/docs/research/probe-0.2.2-rc.1/probe.mjs '<带 token 的链接>'
```

这些脚本已经改写成 `plugin/test/contract/` 的契约测试（`npm run contract`），通过插件的适配层逐项验证上面的接口，并额外验证了：拒绝审批（`rejected`）后命令不执行、`session/cancel` 能停下正在输出的回复、同一个启动 token 可以重复登录。以后验证新版本 DSH 时直接运行契约测试即可，见 [roadmap](../roadmap.md)。

## 9. 手机端对齐桌面功能的可行性（2026-11-10）

用户提供了官方 Desktop 的截图：本轮 token 用量、缓存命中、新对话分支、会话性能、上下文占用，以及「已完成／用时」过程折叠。另已明确创建／导入项目优先于完整文件管理和远程终端。

**验证层级**：本节读取的是官方标签 `dsh-v0.2.2-rc.1` 的源码、对应文档及本仓库此前录制的假模型数据，并运行了本地样本读取与现有 `convertRecords` 转换。不涉及真实账户的模型调用，**没有新跑 `session/fork`、`session/projections`、`session/observeProjections` 或 `workspace/create` 的运行时契约测试**；源码存在不等于接口已实测。

### 9.1 公开数据入口与当前缺口

`session/projections` 返回会话的公开统计与状态投影，`session/observeProjections` 提供更新流；`session/list` 的摘要只包含允许进入摘要的投影，不能假定含有全部统计。

基线源码：[Session Remote 控制器](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.1/packages/api/session-controller/src/index.ts)、[请求与返回类型](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.1/packages/api/session-controller/src/types.ts)。

目前插件的 `facade.js` 只提取 v1 的会话摘要字段，`transcript.js` 只生成文本、思考和工具等消息部件。统计、轮次边界与分支操作尚未进入 App 的外观协议。以后应在适配层提取允许的统计字段，先扩展 `protocol.md` 再实现；不能向手机原样透传请求标头、系统提示词、工具定义或全部 DSH 投影。

### 9.2 token 用量与缓存命中：可做

- 会话累计用量已有 `tokenUsage`，含 `uncachedInputTokens`、`outputTokens`、`cacheReadTokens`、`cacheWriteTokens`。本仓库 `session-list.json` 的非空会话样本实际含有四项，分别为 80、88、0、0；缓存为零来自假模型，不能据此声称已验证真实模型的非零缓存命中。
- 官方界面会话缓存命中率的分母是未缓存输入、缓存读取和缓存写入之和；分子为缓存读取。输出 token 不进入分母；分母为零时不显示百分比。整段会话的比例应从合计 token 算，不能平均每轮百分比。
- 每轮详细用量由 `deriveTurnTokenUsage(events)` 从完整轮次的模型尝试计算。最终消息的用量替换同一次尝试的流式样本，重试是另一次尝试；不能把这些样本简单累加。某项缓存／模型信息未被所有尝试报告时，该可选项可能不存在；不能强行显示为 0。
- 现有历史分页按消息切页，不保证完整轮次。要展示「本轮用量」须补齐相应事件范围，或由插件提供单独的轮次摘要；数据不全时不能展示一个伪装成完整值的小计。
- 累计用量、本轮用量与当前上下文占用是不同的指标。分支继承历史后的累计统计口径也要与官方对齐，不能自动解释为分支创建后新增的费用。

基线依据：[token-meter 文档](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.1/packages/llm/token-meter/README.zh.md)、[逐轮用量](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.1/packages/llm/token-meter/src/turn-usage.ts)、[会话统计界面及缓存比例](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.1/packages/client/ui-chat/src/client/chat/StatsPills.tsx)、[本轮用量界面](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.1/packages/client/ui-chat/src/client/chat/TurnUsagePanel.tsx)。

### 9.3 会话性能与上下文占用：可做，但需标明数据含义

- `sessionStats` 提供轮数、步数、模型／工具耗时、`ttftMs`、`ttftSteps`、`decodeMs`、`decodeTokens`。平均 TTFT 为 `ttftMs / ttftSteps`，TPS 为 `decodeTokens / (decodeMs / 1000)`，仅在分母有效时显示。应使用电脑端记录的计时，不用手机收到流式块的时间计算模型速度。
- `sessionStats` 依赖 DSH 中相应组件；未提供时应显示不可用，或明确标注仅统计已加载窗口，不能把最近一页冒充全会话。
- `contextPressure` 提供 `pressureTokens`、`projectedTokens` 和 `contextWindow`，`contextBreakdown` 提供系统、工具、消息的估算构成。这两项不进入 `session/list` 摘要，需按会话读取。
- 当前上下文规模优先使用估算下次请求的 `projectedTokens`；构成数字是另一种启发式估算，不保证加起来等于占用值。展示需保留「约／~」；模型切换后容量与最近用量可能暂时属于不同请求，不应呈现为精确计费数据。

基线依据：[session-stats 文档](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.1/packages/session/session-stats/README.md)、[上下文投影定义](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.1/packages/llm/token-meter/src/projection.ts)、[上下文界面](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.1/packages/client/ui-conversation/src/client/skeleton/ContextMeter.tsx)。

### 9.3a 用量与上下文的运行时实测（2026-11-14，落地方案 B4）

在真实的 `dsh web` 0.2.2-rc.1 加假模型上实测（契约测试 `npm run contract` 中的两项，以及 `devServerTest`）：

- `session/projections { request: { sessionId } }` 返回 `{ kind: 'sequenced', asOfSeq, values }`。新会话：`tokenUsage` 四项都是 0，`contextPressure` 是空对象（还没有请求，拿不到占用）。跑完一轮后：`tokenUsage` 为 `{ uncachedInputTokens: 10, outputTokens: 5, cacheReadTokens: 0, cacheWriteTokens: 0 }`，`contextPressure` 为 `{ pressureTokens: 10, projectedTokens: 21, contextWindow: 1048576 }`。投影暂时不可用时错误码是 `session/projections-unavailable`。
- `session/observeProjections` 的帧是 `{ type: 'ready' }` 和 `{ type: 'projection', seq, changes: [{ key, val }] }`；一轮里 `contextPressure`、`contextBreakdown` 会变化很多次。插件没有用它，App 在打开会话、一轮结束和长按发送键时读一次 `session/projections` 就够了。
- `session/list` 的摘要投影里有 `tokenUsage`，没有 `contextPressure`、`contextBreakdown`、`sessionStats`，和 9.1 的结论一致。
- 历史分页（`session/page`）里的 `assistant/message` 带 `usage: { inputTokens, outputTokens, totalTokens }` 和 `stream`，`deriveTurnTokenUsage` 能从中算出完整轮次的用量：一步的轮次 15，两步的工具轮次 45（两次请求的合计）；被用户停止、这一步没有结束的轮次算不出来（返回 undefined）。插件把这个函数照抄到了 `plugin/src/vendor/dsh-turn-usage.mjs`（MIT）。
- 假模型不报告缓存，所以**真实模型的非零缓存命中仍未在运行时验证**；口径照 9.2 和官方界面的源码。
- 另外看到的投影：`goal`、`todos`、`plan`（`{ active, pending }`）、`turnOutline`、`imageLimits`。和落地方案 B5（计划）、C2（图片附件）有关，到时候再实测。

### 9.4 新对话分支：公开接口已存在，难点是边界与恢复

- `session/fork` 接受 `{ request: { sessionId, atSeq?, allowMigration? } }`，返回新 `sessionId`。`atSeq` 是包含该条事件的精确整数序号；省略时取最近已完成轮次的前缀。应以当前源码为准，不使用早期设计笔记中「向后寻找 turn/end」的旧语义。
- 官方回复尾部按钮使用已掌握的 `turn/end` 序号。当前 DSHRemote 消息只保留消息 id、角色、内容和时间，没有可靠的轮次结束锚点；不能把消息 id 或分页游标直接当成分支位置。
- 建议首先支持从已完成轮次分支，再评估任意事件位置。空会话、尚未完成的输出、分页缺失边界、源会话迁移和创建请求回执丢失都需要测试。
- Host 将子会话附加到源工作区；存在「子会话已创建，但附加工作区失败」的部分成功状态，返回错误中包含子会话 id。客户端不能盲目重试并生成多个子会话。
- 子会话沿用源工作目录，复制的是对话历史；不会为它新建 Git 分支／worktree，也不会撤销电脑上已经发生的文件修改。

基线依据：[分支请求类型](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.1/packages/api/session-controller/src/types.ts)、[分支执行逻辑](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.1/packages/api/session-controller/src/commands.ts)、[回复尾部按钮](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.1/packages/client/ui-chat/src/client/chat/TurnTailNodeView.tsx)。

### 9.5 思考与整轮过程折叠：前者已实现，后者需要补充轮次模型

- Android `ui/chat/MessageViews.kt` 已把 `ThinkingPart` 放进默认关闭的 `Collapsible`，工具卡片也默认折叠。运行现有转换器处理 `session-page-full.json`，得到 1 个思考部件；没有模型提供的思考内容时不应制造内容。
- 截图中的「已完成，用时 1 秒」是整轮过程的状态／耗时与折叠入口，与单条思考块折叠不同。官方按轮次分组并保留回复，DSHRemote 目前没有对应轮次数据结构。
- 数据来源已存在：该历史样本包含 6 个 `turn/start` 和 6 个 `turn/end`。后续可在适配层输出轮次 id、起止时间、结束原因及消息归属，App 再处理整轮折叠；运行中、失败、等待审批与历史分页不能被错误折叠为「已完成」。
- 这组轮次数据也能供准确结束状态、本轮用量和分支锚点共用，应一起设计协议，避免每项功能单独猜测边界。

基线依据：[官方过程折叠说明](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.1/packages/client/ui-chat/README.md#turn-process-folding)。

### 9.6 创建／导入项目的补充依据

补充：基线 `directoryPicker/list` 和 `directoryPicker/createDirectory` 已有公开入口，但要求 browse 后端；native 后端返回 `directory-picker/unavailable`。因此本项目采用 ADR 0013，由插件提供有限的本机目录操作，登记仍走 `workspace/create`。接口来源：[目录选择控制器](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.1/packages/api/workspace-controller/src/directory-picker.ts)。现有契约测试曾间接调用 `workspace/create` 验证会话归属，本次进一步覆盖外观层登记、重复路径和创建后恢复；最终运行结果见创建／导入项目计划。

基线已有公开 `workspace/create`，用于把**已有目录**注册为工作区，重复路径可幂等解析。它不等于创建磁盘目录，也不能直接把电脑原生目录选择窗口作为手机上的目录选择器。手机目录选择和路径边界已在 ADR 0013、protocol.md 中补充约定。来源：[Workspace Remote 控制器](https://github.com/deepseek-ai/deepseek-harness/blob/dsh-v0.2.2-rc.1/packages/api/workspace-controller/src/index.ts)。
