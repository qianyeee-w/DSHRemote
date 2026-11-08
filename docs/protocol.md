# App ↔ 插件 接口约定（协议 v1，草案）

本文件是 App 和插件之间接口的**单一事实来源**。接口有任何变化，先改这里，再改两端代码。

> 状态：草案。第 4 至 6 节已根据 [DSH 会话接口调研](research/dsh-session-api.md) 和 [ADR 0007](decisions/0007-plugin-facade-api.md) 确定方向（外观层）。字段细节在 M1 实现事件转换器时最终确定，标 **[待定]** 的部分届时补齐。

## 1. 传输

- 插件在局域网端口（默认 `3190`）上**只提供 TLS**（HTTPS / WSS），不提供明文 HTTP。
- 证书：插件首次运行时生成 ECDSA P-256 密钥和自签名证书，保存为 `<DSH_HOME>/dshremote/tls-key.pem` 和 `tls-cert.pem`。
  - 证书有效期 10 年，到期前 30 天内自动用**同一把私钥**重新签发；证书损坏或与私钥不匹配时也会重新签发。这几种情况下指纹都不变。
  - TLS 版本：只接受 TLS 1.2 及以上。
- **指纹**：证书公钥（SubjectPublicKeyInfo，DER 编码）的 SHA-256，用 base64url 编码，不加填充。
  - 固定的是公钥而不是整张证书，所以证书到期后用同一个密钥重新签发，指纹不变，手机不需要重新配对。
  - 只有重新生成密钥时指纹才会变，这时所有手机都要重新配对。
- App 不使用系统的证书信任链，**只校验指纹**；也不校验主机名，因为用户可能用 IP、主机名或 Tailscale 地址连接。

## 2. 配对

### 2.1 二维码

```
dshremote://pair?v=1&h=<地址>&p=<端口>&fp=<指纹>&c=<配对码>&n=<电脑名>
```

| 字段 | 说明 |
|---|---|
| `v` | 协议版本，当前为 `1` |
| `h` | 电脑的地址（一个 IPv4）。电脑有多个地址时，配对页默认按「192.168/16 → 10/8 → 172.16/12 → 100.64/10（Tailscale 等）→ 其他」的顺序选第一个，用户可以在配对页上切换。配置了 `lanBind` 为具体 IP 时只用这个 IP |
| `p` | 端口 |
| `fp` | 证书指纹 |
| `c` | 一次性配对码：8 位，字符集排除容易混淆的 0/O/1/I/L |
| `n` | 电脑名称，仅用于显示 |

手动输入时，用户填写地址、端口、配对码。这种情况下 App 拿不到指纹，处理方式见 2.3。

### 2.2 配对码规则

- 由用户在本机配对页手动生成，5 分钟内有效，**用一次就作废**。只保存在内存中，DSH 重启后不存在有效的配对码。
- 同一时间只有一个有效的配对码；生成新码时旧码立刻失效。
- 连续输错 5 次后，配对码作废，配对接口锁定 1 分钟（不区分来源，全局锁定）。在本机重新生成配对码会立即解除锁定。
- 输入时忽略大小写、空格和连字符。
- 格式不对的请求（缺字段等）不计入错误次数。

### 2.3 手动输入时如何校验指纹

App 先连接服务器取得证书，计算出指纹，用**大号字体显示指纹的前 8 位**，请用户和电脑配对页上显示的指纹核对，确认一致后才发送配对码。

### 2.4 `POST /v1/pair`（无需认证）

请求：

```json
{ "code": "K7M2Q9XP", "deviceName": "Pixel 8", "platform": "android", "appVersion": "0.1.0" }
```

响应 `200`：

```json
{ "deviceId": "d_...", "token": "<随机 32 字节，base64url>", "host": { "name": "MY-PC", "pluginVersion": "0.1.0" } }
```

- token 只在这个响应里出现一次。插件端只保存它的 SHA-256 哈希。
- App 把 token 和指纹保存在 Android Keystore 加密的存储里。
- 字段要求：`code` 为字符串；`deviceName` 1–64 个字符，不能包含控制字符；`platform` 为小写字母开头的 `[a-z0-9-]`，最长 16 个字符（目前只有 `android`）；`appVersion` 为 `[0-9A-Za-z.+-]`，最长 32 个字符。请求体最大 4 KB，`Content-Type` 必须是 `application/json`。
- 错误：配对码错误、已过期或不存在时返回 `400 PAIRING_CODE_INVALID`（不区分这几种情况）；锁定期间返回 `429 PAIRING_LOCKED`，并带上 `Retry-After`（秒）；已配对设备达到上限（20 台）时返回 `409 PAIRING_DEVICE_LIMIT`。

### 2.5 本机配对页

- 地址：`http://127.0.0.1:3191/`（端口由 `adminPort` 配置），**只监听 127.0.0.1**，不对局域网开放。第一次启动时自动在浏览器中打开（`openAdminOnFirstRun`）。
- 功能：生成或取消配对码、显示配对二维码和手动输入所需的信息、显示证书指纹、列出和撤销已配对设备。
- 访问控制：Host 必须是 `127.0.0.1:<端口>` 或 `localhost:<端口>`；会改变状态的操作只接受 POST，并且同时要求 Origin 为配对页自身、`Content-Type` 为 `application/x-www-form-urlencoded`、表单中带有正确的 CSRF token。页面禁止被嵌入框架，不包含任何脚本。

## 3. 认证

- 除了明确标注「无需认证」的接口，所有请求都必须带上 `Authorization: Bearer <token>`。
- WebSocket 也在握手请求头里带这个 header（OkHttp 支持在 WebSocket 握手时设置请求头）。
- token 无效或已被撤销时返回 `401`，错误码是 `TOKEN_INVALID`，并带上 `WWW-Authenticate: Bearer`。App 收到后停止重连，提示用户重新配对。
- 只接受 `Bearer` 加 43 个 base64url 字符这一种格式，大小写敏感。

## 4. 接口总览

插件对 App 提供**外观层**接口（ADR 0007）。App 只依赖本节定义的接口，**不直接接触 DSH 的协议和数据格式**。

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| GET | `/v1/info` | 无需认证 | `{ protocol: 1, pluginVersion, dsh: { baseline, version, compatible } }`，用于显示在线状态和检查版本。插件读不到 DSH 版本时，`version` 和 `compatible` 为 `null` |
| POST | `/v1/pair` | 无需认证 | 见 2.4 |
| GET | `/v1/me` | 需要 | 当前设备的信息：`{ deviceId, name, platform, createdAt }` |
| DELETE | `/v1/me` | 需要 | 设备自行取消配对，成功返回 `204`，之后 token 立即失效 |
| GET | `/v1/workspaces` | 需要 | 工作区列表：`{ workspaces: [{ workspaceId, title, path }] }` |
| GET | `/v1/sessions` | 需要 | 会话列表：`{ sessions: [...] }`，每项的结构见 5.1。已归档的会话不出现（和 Desktop 一致） |
| POST | `/v1/sessions` | 需要 | 新建会话 `{ workspaceId? }`，返回 `{ sessionId }` |
| GET | `/v1/sessions/{id}/messages?before=<cursor>&limit=<n>` | 需要 | 历史消息分页，从最新的消息向前翻，返回 `{ messages, hasMore, cursor }`，消息结构见 5.2。见下方「历史分页」 |
| POST | `/v1/sessions/{id}/prompt` | 需要 | 发消息 `{ requestId, text, mode?: "queue" \| "steer" }`，返回 `{ accepted: true }`。见下方「发消息」 |
| POST | `/v1/sessions/{id}/cancel` | 需要 | 停止当前任务，不需要请求体，返回 `{ accepted: true }`。会话空闲时也返回成功 |
| GET | `/v1/models` | 需要 | 可选的模型列表：`{ default: { provider, model } \| null, models: [{ provider, providerName, model, name, description? }] }` |
| POST | `/v1/sessions/{id}/model` | 需要 | 切换模型 `{ provider, model }`（取值来自 `/v1/models`），返回 `{ model: { provider, model } }`。模型不可用时 `400` |
| GET | `/v1/permission-presets` | 需要 | 权限模式列表：`{ default: string \| null, presets: [{ id, name }] }`，例如 `read-only`、`workspace-write`、`danger-full-access` |
| POST | `/v1/sessions/{id}/permission-preset` | 需要 | 切换权限模式 `{ preset }`（取值来自 `/v1/permission-presets`），返回 `{ permissionPreset }`。不在列表中的值 `400` |
| POST | `/v1/attention/{attentionId}/answer` | 需要 | 处理审批或回答提问，见 5.3，返回 `{ attentionId }` |
| GET | `/v1/stream` | 需要 | WebSocket，所有实时数据都走这一条连接，见第 6 节 |

设备的列表和撤销**只在本机配对页上提供**，不开放给局域网访问。

路径中的 `{id}` 只能由字母、数字、`_`、`-` 组成（最长 128 个字符），否则返回 `404`。

**历史分页**

- `limit`：本页最多包含多少条 DSH 消息，默认 50，范围 1–200。插件会过滤掉 DSH 内部注入的消息，所以返回的 `messages` 可能少于 `limit`，甚至为空，这不代表已经翻到底。**是否还有更早的消息只看 `hasMore`**。
- `cursor`：不透明的字符串，App 不要解析它。`hasMore` 为 `true` 时，把它作为下一次请求的 `before`，取更早的一页；`hasMore` 为 `false` 时为 `null`。
- 不带 `before` 时返回最新的一页。同一个会话的各页按顺序拼起来，就是完整的历史，不会重复，也不会把一次工具调用拆到两页里。

**发消息**

- `requestId`：App 为每条消息生成一个新的 id（建议 UUID，只能由字母、数字、`_`、`-` 组成，最长 128 个字符）。因为网络问题重试同一条消息时，**必须沿用原来的 `requestId`**：插件在 10 分钟内只会把它发出一次，重复的请求直接返回成功。插件重启后这个记录会清空。
- `text`：不能为空，最多 100,000 个字符。
- `mode`：`queue`（默认）在当前任务结束后再处理；`steer` 插话，在当前任务的下一步就让模型看到。
- 会话不存在时返回 `404`。

插件内部通过 DSH 官方的 Remote API 实现以上接口，转换逻辑都在 `plugin/src/dsh/` 中。具体对应关系见 [调研文档](research/dsh-session-api.md) 第 4 节。**App 永远拿不到 DSH 的 cookie 或启动 token。**

## 5. 数据结构

### 5.1 会话摘要

```json
{
  "sessionId": "session-…",
  "title": "修复登录页样式",
  "workspaceId": "… 或 null",
  "cwd": "/home/me/project",
  "status": "running | waiting | idle",
  "attention": [{ "attentionId": "…", "kind": "approval | question" }],
  "model": { "provider": "deepseek-official", "model": "deepseek-flash" },
  "permissionPreset": "workspace-write",
  "updatedAt": 1794107669094
}
```

`title` 在会话还没有标题时为 `null`（App 显示为「新会话」）；`workspaceId` 在会话没有归入任何工作区时为 `null`。`model` 是下一条消息会使用的模型，`permissionPreset` 是当前的权限模式，读不到时都为 `null`。

`status` 为 `waiting` 表示有未处理的审批或提问（`attention` 不为空）。会话列表按「waiting → running → idle」排序，同一状态内按 `updatedAt` 倒序。

### 5.2 消息

插件把 DSH 的原始事件转换成以下结构。App 不需要了解 DSH 的内部事件。

```json
{
  "id": "…",
  "role": "user | assistant",
  "parts": [
    { "type": "text", "text": "…" },
    { "type": "thinking", "text": "…" },
    { "type": "tool", "callId": "…", "name": "bash", "summary": "运行命令：npm test", "status": "running | done | error | rejected", "input": {…}, "output": "…（可能被截断）" }
  ],
  "createdAt": 1794107669094
}
```

- 只包含用户亲自输入的消息和模型的回复。DSH 自动注入的消息（运行时上下文、时间上下文、系统提示等）不显示。
- 用户消息中的图片和文件，v1 用占位文字表示（`[图片]`、`[文件：a.txt]`）。
- `thinking` 和 `tool` 在 App 中默认折叠显示。
- 工具的 `status`：`running` 还在执行；`done` 成功；`error` 失败，或者没执行完这一轮就结束了（例如被停止）；`rejected` 审批被拒绝。
- 工具的 `summary` 是一行给人看的说明（例如「运行命令：npm test」「读取：src/a.js」），插件不认识的工具只显示工具名。
- 工具的 `input` 和 `output` 可能很大，插件按上限截断，并标记 `truncated: true`：`input` 中每个字符串字段最多 2,000 个字符，`output` 最多 8,000 个字符，截断处以 `…` 结尾。
- DSH 的内部事件和遥测事件（如 `request/header`、`session-log-*`）**不转发给 App**。

### 5.3 待处理事项（attention）

审批和提问统称「待处理事项」，用统一的 `attentionId` 标识。

```json
{
  "attentionId": "…",
  "sessionId": "…",
  "kind": "approval",
  "approval": {
    "toolName": "bash",
    "reason": "允许本次操作使用 danger-full-access 权限：…",
    "summary": "运行命令：npm install",
    "input": { "command": "npm install", "description": "…" }
  },
  "createdAt": 1794107669094
}
```

```json
{
  "attentionId": "…",
  "sessionId": "…",
  "kind": "question",
  "question": {
    "questions": [{ "id": "q1", "question": "用哪种方案？", "header": "选择", "options": [{ "label": "方案A", "description": "…" }], "multiSelect": false }]
  },
  "createdAt": 1794107669094
}
```

- 审批的 `reason` 优先使用中文。DSH 的审批请求里看不到要执行的命令，插件会从会话日志中找到对应的工具调用，补上 `summary` 和 `input`（截断规则同 5.2）；找不到时这两个字段不出现，App 只显示 `toolName` 和 `reason`。
- `sessionId` 对子代理（subagent）发起的事项可能是子代理的 id，不在会话列表中。v1 的 App 照样显示和处理即可。

回答（`POST /v1/attention/{attentionId}/answer`）：

- 审批：`{ "decision": "allow" | "reject" }`。DSH 的审批**只对当前这一个请求有效**，没有「以后都允许」这个选项。想减少审批次数，要去切换权限模式。
- 提问：`{ "answers": [{ "id": "q1", "selected": ["方案A"], "custom": "可选的自由文本" }] }`。`id` 必须属于这个提问，`selected` 只能是选项中的 `label`，单选题最多选一项；每个问题至少选一项或填写 `custom`（最多 4,000 个字符）。不需要回答全部问题。
- 格式不对时返回 `400`，事项保持待处理。

如果这项事项已经在别处被处理了（例如在 Desktop 上点了批准），或者任务已经被停止，返回 `409`，错误码 `ATTENTION_RESOLVED`。从来没有出现过的 `attentionId` 返回 `404`。

两边同时处理时，只有一边生效：插件保证手机收到 `200` 时，生效的一定是手机的回答。为此插件在 DSH 接受回答后会多等约 0.3 秒，确认没有被别处抢先，所以回答接口的响应会比其他接口稍慢。

> 安装插件后，插件一直以 owner 身份连着 DSH，DSH 会认为「有人能回答审批」。没有任何窗口和手机在线时，审批会一直等待，而不是像以前那样直接失败。这正是远程处理审批需要的行为，`security.md` 中有说明。

## 6. 实时流 `/v1/stream`

App 只维持**一条** WebSocket 连接，前台服务和界面共用。

### 6.1 插件 → App

| 消息 | 时机 |
|---|---|
| `hello` | 连接建立后立刻发送：`{ protocol, sessions: [会话摘要...], attention: [待处理事项...] }`，即当前的完整状态 |
| `session.updated` | 会话摘要发生变化（状态、标题等），附带新的会话摘要 |
| `session.removed` | 会话被删除 |
| `attention.requested` | 出现新的待处理事项，附带完整的事项内容 |
| `attention.resolved` | 某个事项已经处理完：`{ attentionId, by: "this-device" \| "other" }`。`other` 表示在别处处理掉了，或者任务被停止（DSH 0.2.2 不区分这两种情况）。App 收到后收起对应的界面和通知 |
| `session.idle` | 会话从运行变为空闲，并持续 8 秒（用于发出「任务完成」通知；8 秒内又开始运行就不发） |
| `message.upsert` | **只对已订阅的会话发送**：新增或更新一条消息（结构见 5.2）。流式输出时，同一条消息会多次发送，并带上递增的 `rev` |
| `error` | 出错，附带错误码 |

所有消息都带 `eventId`，App 用它来去重。

### 6.2 App → 插件

| 消息 | 说明 |
|---|---|
| `subscribe` | `{ sessionId }`：打开会话详情页时订阅，开始接收这个会话的 `message.upsert` |
| `unsubscribe` | `{ sessionId }`：离开会话详情页时取消订阅 |

前台服务**不订阅**任何会话，只接收状态和待处理事项，以节省电量和流量。

### 6.3 重连

连接断开后，App 重新连接，并重新发送 `subscribe`。插件会发送新的 `hello`，App 以它为准**覆盖**本地的状态。已订阅会话的消息由 App 调用 `/v1/sessions/{id}/messages` 补齐 **[待定：是否支持「从某个 cursor 之后续传」以减少重复传输]**。

## 7. 错误格式

```json
{ "error": { "code": "TOKEN_INVALID", "message": "人类可读的说明" } }
```

| 错误码 | HTTP 状态码 | 含义 |
|---|---|---|
| `TOKEN_INVALID` | 401 | token 无效或已被撤销 |
| `PAIRING_CODE_INVALID` | 400 | 配对码错误或已过期 |
| `PAIRING_LOCKED` | 429 | 输错次数过多，接口暂时锁定（带 `Retry-After`） |
| `PAIRING_DEVICE_LIMIT` | 409 | 已配对设备达到上限（20 台） |
| `ATTENTION_RESOLVED` | 409 | 这项审批或提问已经在别处处理掉了 |
| `DSH_UNAVAILABLE` | 503 | DSH 还没启动完成，或插件暂时连不上 DSH |
| `DSH_ERROR` | 502 | DSH 拒绝了这个操作，或返回了插件无法处理的结果（`message` 中只带 DSH 的错误码，不带原始错误信息） |
| `DSH_INCOMPATIBLE` | 503 | DSH 版本不兼容 |
| `NOT_FOUND` | 404 | 接口不存在（也用于会话或待处理事项不存在） |
| `METHOD_NOT_ALLOWED` | 405 | 不支持该请求方法，`Allow` 响应头列出支持的方法 |
| `BAD_REQUEST` | 400 | 请求格式错误，包括缺少字段、字段格式不对、不是合法的 JSON |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | `Content-Type` 不是 `application/json` |
| `PAYLOAD_TOO_LARGE` | 413 | 请求体过大 |
| `INTERNAL` | 500 | 插件内部错误（不附带细节） |

`message` 里不得包含 token、配对码或任何路径之类的敏感信息。

## 8. 版本兼容

- App 连接后先调用 `/v1/info`，`protocol` 字段不认识时提示用户升级。
- 协议出现不兼容的修改时，路径前缀升级为 `/v2`。插件至少同时支持当前和上一个版本。
