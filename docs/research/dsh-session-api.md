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
- 插件是通过本机 HTTP 加 mux 调用 DSH，还是在进程内通过 `connection.createPeer()` 直接调用。两种都是公开接口，M1 中再比较。

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

这些脚本就是将来 `plugin/test/contract/` 契约测试的雏形，见 [roadmap](../roadmap.md)。
