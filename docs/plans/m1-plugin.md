# M1 方案：电脑端插件

- 状态：已确认（2026-11-08）
- 日期：2026-11-08
- 范围：`docs/spec-v1.md` 中「电脑端插件」一节，以及 `docs/protocol.md` 定义的全部接口
- 完成标志：本文第 6 节的验收清单全部通过

## 1. 技术选型

| 项目 | 选择 | 理由 |
|---|---|---|
| 语言 | 纯 JavaScript（ESM），文件头加 `// @ts-check`，用 JSDoc 写类型 | 和 DSH 插件生态一致；不需要编译，安装后直接从源码目录加载；类型检查仍然可以做 |
| 类型检查 | `tsc --noEmit`（`checkJs`，严格模式），只作为开发依赖 | 在 CI 中拦住类型错误 |
| Lint | ESLint（flat config），只作为开发依赖 | |
| 测试 | `node:test` + `node:assert` | 不引入测试框架 |
| WebSocket | **`ws@8`**（MIT，没有子依赖）——**唯一的运行时依赖** | Node 22 只内置了 WebSocket 客户端，没有服务端；`/v1/stream` 服务端和 DSH mux 客户端都用它 |
| 二维码 | 放进 `src/vendor/` 的 `qrcode-generator`（MIT，保留许可证声明） | 已在 dsh-tailnet-remote 中用过 |
| 自签名证书 | 自己写一个最小的 DER 编码器（已确认，见 4.1） | 不增加依赖 |

运行时依赖只有 `ws` 一个。从本地目录安装时，需要在插件目录下先运行一次 `npm install --omit=dev`；发布时改为打包成 tgz（附带依赖）。

## 2. 目录结构

```
plugin/
├── index.js                 # 插件入口：name / inject / apply，只负责组装和生命周期
├── cordis.patch.yml         # 插件挂载与默认配置
├── package.json
├── src/
│   ├── config.js            # 配置校验（非法值直接报错）
│   ├── storage.js           # <DSH_HOME>/dshremote/ 下的文件读写（原子写入；Linux 上权限设为 0600）
│   ├── tls/
│   │   ├── certificate.js   # 生成 ECDSA P-256 密钥和自签名证书
│   │   └── fingerprint.js   # SPKI SHA-256 指纹（base64url）
│   ├── pairing/
│   │   ├── codes.js         # 配对码：生成、5 分钟过期、用一次就作废、输错 5 次锁定
│   │   └── devices.js       # 设备登记、token 哈希、撤销
│   ├── server/
│   │   ├── https.js         # 局域网 HTTPS 服务（只提供 TLS）
│   │   ├── auth.js          # Bearer token 校验
│   │   ├── routes.js        # /v1/* 路由表（外观层操作的白名单）
│   │   └── stream.js        # /v1/stream WebSocket
│   ├── admin/
│   │   └── page.js          # 本机配对页（只监听 127.0.0.1）
│   ├── dsh/                 # DSH 适配层：所有对 DSH 的访问都只能在这里
│   │   ├── versions.js      # 支持的 DSH 版本范围（唯一定义处）和启动时的版本检查
│   │   ├── connection.js    # 登录（启动 token → cookie）、一元调用、mux、$events、断线重连
│   │   ├── sessions.js      # 会话列表和状态汇总
│   │   ├── attention.js     # 审批和提问：waterfall → attentionId，回答，多端撤回
│   │   ├── transcript.js    # 原始会话事件 → protocol.md 5.2 的消息结构（核心）
│   │   └── facade.js        # 外观层各个操作的实现，供 server/routes.js 调用
│   └── vendor/qrcode.mjs
└── test/
    ├── unit/
    ├── contract/            # 对真实 DSH 运行：DSH_VERSION 指定版本，输出 JSON 结果
    │   └── fake-llm.mjs     # 由 docs/research 中的探测脚本整理而来
    └── e2e/                 # 插件装进 dsh web，模拟手机走完整个流程
```

**对应 roadmap 中的预留设计：**DSH 的访问只在 `src/dsh/`；版本范围只在 `versions.js` 定义；`connection.js` 是所有 DSH 调用的统一入口，每次调用都带上 `deviceId`；`routes.js` 中的每个操作都对应到一个明确的 DSH 接口。

## 3. 配置（`cordis.patch.yml` 默认值）

| 键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | |
| `lanPort` | `3190` | 局域网 HTTPS 端口 |
| `lanBind` | `0.0.0.0` | 监听地址。可以改成某个网卡的 IP，只在这个网卡上提供服务 |
| `adminPort` | `3191` | 本机配对页的端口，只监听 `127.0.0.1` |
| `openAdminOnFirstRun` | `true` | 第一次启动时，自动在浏览器里打开配对页 |

## 4. 设计决定

### 4.1 自签名证书怎么生成

Node 自带的 `crypto` 能生成密钥、能解析证书，但**不能生成证书**。可选方案：

| 方案 | 优点 | 缺点 |
|---|---|---|
| **A. 自己写一个最小的 DER 编码器（✅ 已选定）** | 不增加依赖；代码大约 150 行，只生成一种固定格式的证书（ECDSA P-256 + SHA-256，自签名），审查起来容易 | 要自己写 ASN.1 编码。用测试兜底：生成的证书要能被 Node 的 `X509Certificate` 解析、能用自己的公钥验证签名，并且能完成一次真实的 TLS 握手 |
| B. 引入 `@peculiar/x509` | 现成、规范 | 子依赖十几个，运行时依赖从 1 个变成十几个 |
| C. 调用系统的 `openssl` | 代码最少 | Windows 默认没有 openssl |

这里的风险不大：App 只校验**公钥指纹**，不校验证书的其他字段。即使证书格式有小瑕疵，只要 TLS 握手能成功，安全性就不受影响。

### 4.2 本机配对页的 CSRF 防护

配对页只监听 `127.0.0.1`，并且校验 Host，这能挡住 DNS rebinding。但是，**你电脑浏览器里打开的恶意网页仍然可以向 `http://127.0.0.1:3191` 发 POST 请求**，例如偷偷生成一个配对码，或者撤销你的设备。

计划：所有会修改状态的操作（生成配对码、撤销设备）都要求 ① `Origin` 必须是配对页自己的地址；② 请求里必须带上页面中嵌入的一次性 token。

### 4.3 提交方式

**已确定：继续直接推送到 `main`。**每个任务完成后单独提交一次，提交前必须在本地跑通该任务的全部测试、lint 和类型检查（CI 只能在推送之后发现问题，所以本地验证不能省）。

## 5. 任务拆分（按顺序，每项单独提交）

| # | 任务 | 主要内容 | 测试 |
|---|---|---|---|
| T1 | 骨架 + CI | package.json、ESLint、tsc 类型检查、一个空插件（能装进 `dsh web` 并正常加载）、GitHub Actions（ubuntu + windows，Node 22） | CI 跑通；插件能在 dsh web 中加载 |
| T2 | 配置与存储 | config.js、storage.js（原子写入；Linux 上权限 0600） | 单元测试：非法配置报错；写入中途崩溃时不会留下损坏的文件 |
| T3 | TLS | 证书生成（按 4.1 的选择）、指纹计算、HTTPS 服务、`/v1/info` | 指纹固定的客户端：指纹正确时能连上，**指纹错误时拒绝连接**；局域网端口上的明文 HTTP 请求被拒绝 |
| T4 | 配对与认证 | 配对码、设备 token（只存哈希、恒定时间比较）、`/v1/pair`、认证中间件、`/v1/me` | 配对码过期、重复使用、输错锁定；token 错误或被撤销时返回 401；响应和日志中都不出现 token 和配对码 |
| T5 | 本机配对页 | 二维码、生成配对码、设备列表、撤销设备、CSRF 防护 | 来自其他 Origin 的 POST 被拒绝；没有页面 token 的请求被拒绝；Host 不是回环地址时被拒绝 |
| T6 | DSH 适配层基础 | connection.js（登录、一元调用、mux、`$events`、DSH 重启或换端口后自动重连）、versions.js | 契约测试：对真实 dsh 0.2.2-rc.1 完成登录、调用和事件订阅；重启 DSH 后能自动恢复 |
| T7 | 外观层：只读 | `/v1/sessions`、`/v1/workspaces`、`/v1/models`、`/v1/permission-presets`、`/v1/sessions/{id}/messages`（包括 transcript.js 的历史部分） | 契约测试 + 转换器的单元测试（用实测得到的事件作为测试数据） |
| T8 | 外观层：写操作 | 新建会话、发消息（queue / steer）、停止、切换模型、`/v1/attention/{id}/answer`（包括 409 已处理） | 契约测试：在假模型驱动下完成提问和审批；另一个客户端先处理时返回 409 |
| T9 | `/v1/stream` | hello、session.updated、attention.requested / resolved、session.idle（8 秒防抖）、subscribe → message.upsert（流式） | e2e：模拟手机走完整个流程；断线重连后用 hello 覆盖状态 |
| T10 | 收尾 | 在 Windows 的官方 Desktop 上实机验证、补齐「待定」项、更新文档 | 第 6 节验收清单 |

**T6 中的版本检查**：插件运行时如何拿到 DSH 的版本号，还需要调研（在 Desktop 中 DSH 被打包在 app.asar 里）。如果拿不到，退而依靠 `engines.dsh` 声明加上启动时的接口探测：调用一个无副作用的接口，确认返回格式符合预期。

> **结论（T6 已实现）**：插件运行在 DSH 的进程里，从 `process.argv[1]`（DSH 的 JS 入口）向上查找 `@deepseek-ai/dsh` 或 `@deepseek-ai/dsh-desktop-host` 的 `package.json` 读出版本（DSH 所有包同版本发布）。`dsh web` 上实测可读到；Desktop 上的效果在 T10 真机验证。读不到时 `/v1/info` 中的 `version`、`compatible` 为 `null`，配对页显示「未知版本」，不额外调用接口探测——插件连上 DSH 后配对页显示的连接状态已经能说明接口是否可用。DSH 重启或换端口后的自动恢复由单元测试（模拟的 DSH）覆盖；契约测试覆盖真实 DSH 的各项接口。

**T10 的真机验证步骤**见 [m1-windows-verification.md](m1-windows-verification.md)，验证脚本是 `plugin/scripts/verify.mjs`。

## 6. M1 验收清单

- [ ] 插件在 Linux 的 `dsh web` 和 Windows 的官方 Desktop 上都能安装、加载
- [ ] 本机配对页能显示二维码、生成配对码、列出设备、撤销设备
- [ ] 用测试客户端模拟手机：配对 → 列出会话 → 新建会话 → 发消息 → 看到流式输出 → 处理提问和审批 → 收到 session.idle
- [ ] 同一个审批在两个客户端上同时出现，其中一个处理后，另一个收到 attention.resolved
- [ ] 重启 DSH（端口变化）后，客户端不需要重新配对就能自动恢复
- [ ] 撤销设备后，这台设备立刻无法再访问
- [ ] 指纹错误、token 错误、配对码错误、跨站 POST，全部有反向测试并且通过
- [ ] CI 在 ubuntu 和 windows 上全部通过
- [ ] `protocol.md` 中的「待定」项全部定下来，或者明确移到 v2

## 7. 不在 M1 范围内

Android App（M2）；图片附件（v2）；DSH 新版本自动检测（roadmap 第 1 条）；非 owner 的受限身份（roadmap 第 2 条）。
