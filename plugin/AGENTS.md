# plugin/ — 电脑端插件

DSH 插件（Node，ESM，纯 JavaScript + JSDoc 类型），装在官方 Desktop 或 `dsh web` 上。动手前先读根目录的 `AGENTS.md`，以及实施方案 `docs/plans/m1-plugin.md`。

## 命令

```sh
cd plugin
npm install          # 开发环境
npm run lint         # ESLint
npm run typecheck    # tsc --noEmit（checkJs，严格模式）
npm test             # 单元测试：node --test test/unit
npm run check        # 以上三项一起跑。提交前必须通过
npm run load-check   # 把插件装进真实的 dsh web（基线版本），确认插件被加载且处于 active 状态
                     # DSH_VERSION=0.2.3 指定其他版本；DSH_BIN=<dsh 的 JS 入口> 复用已安装的 DSH
                     # DSH_INSTALL_DIR=<目录> 把 DSH 装在固定目录并复用，避免每次重新安装
                     # 同时检查插件已通过适配层连上 DSH（配对页显示「已连接」，/v1/info 报告正确的 DSH 版本）
npm run contract     # DSH 接口契约测试：假模型服务驱动真实 dsh web，通过适配层逐项验证插件依赖的接口
                     # 环境变量同 load-check；stdout 最后一行是 JSON 结果，失败的项目就是适配层要改的地方
npm run e2e          # 端到端测试：插件装进真实 dsh web，模拟手机走完配对 → 实时流 → 流式输出 → 任务完成通知
                     # → 提问和审批（含在 Desktop 上处理）→ DSH 重启后恢复 → 撤销设备。环境变量同 load-check
npm run capture-fixtures  # 从真实 dsh web 重新录制单元测试用的数据，写到 test/fixtures/dsh-<版本>/
                     # 转换器（transcript.js、facade.js）的单元测试只用录制的数据，不手写 DSH 的格式
```

`load-check`、`contract`、`e2e` 和用 npm 版 `dsh` 安装插件，都要求 **PATH 上有 pnpm**（`npm install -g pnpm`）。npm 版的 `dsh plugin add` 内部会调用 pnpm；官方 Desktop 自带依赖管理，不受影响。

**提交前**：本任务的 `npm run check` 必须全部通过；改动涉及加载时，还要跑通 `npm run load-check`；改动涉及 DSH 接口（`src/dsh/`）时，还要跑通 `npm run contract`；改动涉及实时流、配对或对外接口时，还要跑通 `npm run e2e`。我们直接推送 `main`，CI 只能事后发现问题，所以本地验证不能省。

## 目录

完整目录规划见 `docs/plans/m1-plugin.md` 第 2 节。当前已有：

```
plugin/
├── index.js              # 插件入口：name / inject / apply，只负责组装和生命周期
├── cordis.patch.yml      # 插件挂载与默认配置
├── src/
│   ├── config.js         # 配置校验：非法值、未知键直接报错，不静默回退
│   ├── storage.js        # <DSH_HOME>/dshremote/ 下的状态文件：原子写入、POSIX 0700/0600、损坏时报错
│   ├── tls/
│   │   ├── der.js        # 最小 DER 编码器（只够生成一种证书）
│   │   ├── certificate.js# 自签名证书签发、续期判断、SPKI 指纹
│   │   └── identity.js   # 加载或创建 TLS 身份；续期时沿用原私钥，指纹不变
│   ├── admin/
│   │   ├── server.js     # 本机配对页服务：只监听 127.0.0.1；Host 校验 + Origin / Content-Type / CSRF token 三重校验
│   │   ├── page.js       # 配对页 HTML（服务端渲染、无脚本、全部转义）与配对二维码内容
│   │   ├── addresses.js  # 选出放进二维码的本机地址
│   │   └── open-browser.js # 第一次启动时打开配对页（不经过 shell）
│   ├── vendor/           # 原样复制的第三方代码，见 vendor/README.md
│   ├── pairing/
│   │   ├── codes.js      # 一次性配对码：5 分钟、用一次作废、输错 5 次锁定 1 分钟（只在内存中）
│   │   └── devices.js    # 设备登记：token 只存 SHA-256、恒定时间比较、撤销、上限 20 台
│   ├── server/
│   │   ├── https.js      # 局域网 HTTPS 服务（只提供 TLS 1.2+）
│   │   ├── body.js       # JSON 请求体读取与字段校验（大小、Content-Type、控制字符）
│   │   ├── routes.js     # /v1/* 路由表：外观层操作的白名单 + Bearer token 认证
│   │   └── stream.js     # /v1/stream：握手认证、拒绝带 Origin 的握手、连接数和消息大小限制、心跳、撤销即断开
│   └── dsh/              # DSH 适配层：插件访问 DSH 的唯一入口
│       ├── versions.js   # 支持的 DSH 版本范围（唯一定义处）、从入口路径读取 DSH 版本
│       ├── connection.js # 登录（启动 token 换 cookie）、一元调用、mux 流、$events 订阅与回答、断线重连
│       ├── facade.js     # 外观层：protocol.md 的操作 → DSH 接口调用 → protocol.md 的数据结构；DSH 错误 → FacadeError；发消息去重
│       ├── live.js       # 实时状态：会话摘要的变化、任务完成（8 秒）、待处理事项广播；会话消息的增量推送和流式草稿
│       ├── attention.js  # 待处理事项（审批、提问）：跟踪 waterfall / cancel、重连后重新同步、回答与 409 判断
│       └── transcript.js # DSH 会话日志 → 消息结构（protocol.md 5.2），以及分页切点的修正
└── test/
    ├── unit/             # 单元测试（*.test.js）
    ├── helpers/          # 测试辅助：pinned-client.js（按指纹固定证书的客户端，校验方式与 App 一致）
    │                     #   fake-dsh.js（模拟 DSH 的 HTTP + mux，单元测试用）
    │                     #   dsh-runtime.mjs（安装并启动真实 dsh web，load-check 和 contract 共用）
    │                     #   pinned-client.js 也提供 pinnedWebSocket（按指纹连接 /v1/stream）
    │                     #   stub-facade.js（不涉及 DSH 的路由测试用）
    ├── fixtures/         # 从真实 DSH 录制的数据（npm run capture-fixtures），按 DSH 版本分目录
    ├── load/             # 加载检查：装进真实 dsh web，确认 active、连上 DSH，并按指纹访问 /v1/info
    ├── e2e/              # 端到端测试：模拟手机，只通过插件对外的接口和本机配对页操作
    └── contract/         # 契约测试：fake-llm.mjs（假模型服务）+ contract.mjs；capture-fixtures.mjs（录制测试数据）
```

## 约定

- 每个 JS 文件第一行写 `// @ts-check`，用 JSDoc 标注类型。类型检查不通过，不能提交。
- 运行时依赖只允许 `ws`。其他库要么放进 `src/vendor/` 并保留原许可证声明，要么先写 ADR。开发依赖不受此限制。
- 使用的 DSH 服务只有 `webServer` 和 `connection`，写在 `inject` 里（单元测试会检查）。不允许访问其他私有字段。
- **所有对 DSH 的调用都必须放在 `src/dsh/` 适配层里**，其他模块只能调用适配层，不能直接访问 DSH。支持的 DSH 版本范围只在 `src/dsh/versions.js` 中定义，`package.json` 的 `engines.dsh` 必须和它一致（单元测试会检查）。这是为以后的「DSH 新版本自动适配」预留的设计，见 `docs/roadmap.md`。
- 测试代码（`test/` 下）可以直接调用 DSH 的接口，用来做验证；插件代码不可以。
- DSH 的端口每次启动都可能变化，**每个请求都要实时读取** `ctx.webServer.port`，不要缓存（`DshConnection` 已经这样做）。DSH 自己开启 TLS（`ctx.webServer.protocol` 为 `https:`）时，v1 视为 DSH 不可用。
- 一个 `DshConnection` 只打开一条 `$events` 流，所有模块通过 `onEvent()` 共享它。不要为了订阅事件再建一个 `DshConnection`：DSH 会把它当成另一个客户端，回答审批时另一条流会收到 cancel。
- `DshConnection` 的每次调用都要传 `actor`：替哪台设备调用就传它的 `deviceId`，插件自身的调用传 `PLUGIN_ACTOR`。现在都以 owner 身份访问 DSH，这个参数是为以后的受限身份预留的（`docs/roadmap.md` 第 2 条）。
- 插件的生命周期用 `ctx.effect()` 管理。加载时不要阻塞等待 DSH 启动，在后台轮询即可。
- **日志**：使用 `ctx.logger`，并且带上 `[dshremote]` 前缀。`dsh web` 默认不输出插件的 info 日志，所以排查问题时不能指望日志；需要让用户看到的状态，要显示在本机配对页上。
- 状态文件一律通过 `src/storage.js` 读写，不要直接用 `fs` 写状态目录。设备 token 只存哈希。
- 路由不直接调用 `DshConnection`，只调用 `src/dsh/facade.js`。外观层抛出的 `FacadeError` 由路由原样转成错误响应，`DshUnavailableError` 转成 503；`FacadeError` 的 message 里不放 DSH 的原始错误信息。
- **新增 `/v1/*` 接口只能通过 `src/server/routes.js` 的路由表**，并且必须先写进 `docs/protocol.md`。`auth: false` 只能用于 protocol.md 中标注「无需认证」的接口。
- 错误响应统一用 `sendError()`，`message` 中不得出现 token、配对码、文件路径或原始异常信息。
- 新增配置项时，同时修改 `src/config.js` 的 `DEFAULT_CONFIG` 和 `cordis.patch.yml`（单元测试会检查两者一致）。
- 插件代码要能同时在 Windows 和 Linux 上运行：路径一律用 `path.join` 拼接；启动子进程时，用 `process.execPath` 运行 JS 入口，不要依赖 shell 或 `.cmd` 包装。

## 安装方式

- **官方 Desktop**：先在插件目录下运行 `npm install --omit=dev`，再在 Desktop 中打开「插件 → 安装第三方插件」，填写插件目录的绝对路径。npm 版的 `dsh` 命令不允许管理 `desktop` profile。
- **`dsh web`**：`dsh plugin --profile web add -w <插件目录的绝对路径>`（需要 PATH 上有 pnpm）

## 可复用的参考实现

之前写过的 `dsh-tailnet-remote`（Tailscale 版）已经在 DSH 0.2.2-rc.1 上验证过下面这些做法：读取 DSH 端口、用 `authenticatedUrl()` 换取 DSH cookie、把 Host / Origin 改写为回环地址后转发、转发 WebSocket、本机页面的访问控制。DSH 接口的实测结论见 `docs/research/dsh-session-api.md`。
