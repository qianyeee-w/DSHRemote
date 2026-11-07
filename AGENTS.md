# DSHRemote — Agent 指南

用 Android 手机通过**局域网**安全地操控电脑上的 DeepSeek Harness（DSH）：看会话、发消息、处理审批和提问、收通知。

这份文件是所有 coding agent（Claude Code、DSH、Codex 等）的入口。动手之前先读完它，再按需阅读 `docs/` 中的文档。

## 架构

```
Android App ──HTTPS / WSS（证书指纹固定 + 设备 token）──> 电脑端插件（局域网固定端口）
                                                              │ 回环转发，由插件持有 DSH 的官方会话
                                                              ▼
                                                     DSH Web 服务 127.0.0.1:<随机端口>
```

- `plugin/`：电脑端插件（Node，DSH 插件）。装在官方 Desktop 或 `dsh web` 上，在局域网端口提供 TLS 服务，负责配对和设备 token 认证，再把请求转发给 DSH。
- `android/`：Android App（Kotlin + Jetpack Compose）。
- 网络连通由用户自己解决（同一 Wi-Fi、Tailscale、VPN 等均可）。**安全不依赖网络**，见 `docs/security.md`。

## 必读文档

| 文档 | 内容 |
|---|---|
| `docs/spec-v1.md` | 第一版功能范围，**超出范围的功能不要做** |
| `docs/protocol.md` | App ↔ 插件 的接口约定（单一事实来源） |
| `docs/security.md` | 威胁模型与安全红线 |
| `docs/decisions/` | 已确定的架构决策（ADR）；要推翻某条决策，先新写一条 ADR，不要直接改代码 |

各子项目自己的命令和约定写在 `plugin/AGENTS.md` 和 `android/AGENTS.md` 里。

## 版本基线

- DSH：`0.2.2-rc.1`（Session V3）。只使用 DSH 的公开服务接口，**不修改 DSH 的内部字段**。
- Node：≥ 20。
- 插件运行平台：Windows、Linux。v1 不适配 macOS。
- Android：minSdk 26（Android 8.0），Kotlin + Jetpack Compose。

升级任何基线版本都要单独提交，并在 `docs/decisions/` 中记录。

## 安全红线（违反任何一条都不能合并）

1. 不得编写信任所有证书的 TrustManager 或 HostnameVerifier，也不得跳过证书指纹校验。
2. 证书指纹不匹配时直接拒绝连接，**不提供「仍然继续」选项**。
3. 插件不得在局域网端口上提供未经认证的接口。只有 `docs/protocol.md` 中明确标注为「无需认证」的接口可以例外。
4. 不得输出明文 HTTP 到局域网；局域网端口只提供 TLS。
5. token、配对码、私钥、DSH cookie 不得写入日志、提交到仓库或出现在错误信息中。
6. 设备 token 在电脑端只存哈希。
7. 不得调用、修改 DSH 的私有字段或内部数组（例如 `trustedHosts`、cookie 有效期）。

## 工作方式

- **一次只做一个任务**，范围以 `docs/spec-v1.md` 为准。需求不清楚时先提问，不要自行扩大范围。
- 较大的改动先给出方案：改哪些文件、怎么测试，确认后再动手。
- **先改协议，再改实现**：接口变化先更新 `docs/protocol.md`，再同步修改两端代码。
- 提交信息格式：`<type>(<scope>): <摘要>`
  - type：`feat` / `fix` / `refactor` / `test` / `docs` / `chore`
  - scope：`plugin` / `android` / `docs` / `ci`
  - 示例：`feat(plugin): 实现配对接口`

## 完成标准（Definition of Done）

一个任务只有同时满足以下条件才算完成：

1. 相关子项目的测试和 lint 全部通过（命令见子项目的 AGENTS.md）。
2. 新增的行为有对应测试；安全相关逻辑必须有**反向用例**（例如指纹错误时会拒绝连接）。
3. 接口或行为有变化时，`docs/` 已同步更新。
4. 没有违反上面任何一条安全红线。

不要在测试没有实际运行通过时声称任务已完成。

## 第一版不做的事

中转服务器、P2P/NAT 穿透、Noise 协议、云推送（FCM）、iOS、插件适配 macOS、远程终端、文件写入、插件管理、主题系统。即使看起来顺手，也不要做。
