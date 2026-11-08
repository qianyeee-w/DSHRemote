# 0008 v1 插件以 owner 身份访问 DSH

- 状态：已接受
- 日期：2026-11-08
- 依据：[DSH 0.2.2-rc.1 会话接口调研](../research/dsh-session-api.md) 5.4

## 背景

DSH 有两种身份：

- **owner**：本机用户，不受任何权限检查。
- **非 owner**：例如 liveshare 的分享读者，由 `remote-access` 包按角色限制权限。

理想的做法是给每部手机一个非 owner 的受限身份。但在 0.2.2-rc.1 中，非 owner 的身份收不到可回答的 waterfall 事件，也就是审批和提问；调用 `$events/result` 会以 `gateway/invocation-unavailable` 失败。处理审批和提问是 v1 的核心功能。

## 决策

v1 的插件以 **owner** 身份访问 DSH：使用 `ctx.connection.authenticatedUrl()` 拿到启动 token，换成 owner cookie。

手机能做什么，由插件的外观层来限制。外观层只开放 `docs/protocol.md` 中列出的操作，不提供通用的 DSH 调用通道（安全红线第 8 条）。

## 理由

- 只有 owner 能处理审批和提问。
- 外观层本来就只开放有限的操作，可以在插件这一层起到受限身份的作用。

## 代价 / 后果

- 设备 token 泄露时，攻击者能执行外观层开放的全部操作。插件一旦有漏洞（例如外观层意外放行了不该放行的调用），DSH 自己不会再拦一道。
- 因此，token 的保管和撤销、配对码的防护，以及外观层的操作白名单，都是安全审查的重点（见 `docs/security.md`）。
- 以后改用非 owner 的受限身份，见 [roadmap 第 2 条](../roadmap.md)。届时新写一条 ADR 取代本条。
