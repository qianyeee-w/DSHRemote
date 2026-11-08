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
```

以下命令在后续任务中加入：`npm run contract`（T6，DSH 接口契约测试）、`npm run e2e`（T9，端到端测试）。

**提交前**：本任务的 `npm run check` 必须全部通过；改动涉及加载或 DSH 接口时，还要跑通 `npm run load-check`。我们直接推送 `main`，CI 只能事后发现问题，所以本地验证不能省。

## 目录

完整目录规划见 `docs/plans/m1-plugin.md` 第 2 节。当前已有：

```
plugin/
├── index.js              # 插件入口：name / inject / apply，只负责组装和生命周期
├── cordis.patch.yml      # 插件挂载与默认配置
├── src/
│   └── dsh/
│       └── versions.js   # 支持的 DSH 版本范围（唯一定义处）
└── test/
    ├── unit/             # 单元测试（*.test.js）
    └── load/             # 加载检查脚本
```

## 约定

- 每个 JS 文件第一行写 `// @ts-check`，用 JSDoc 标注类型。类型检查不通过，不能提交。
- 运行时依赖只允许 `ws`（从 T6 起引入）。其他库要么放进 `src/vendor/` 并保留原许可证声明，要么先写 ADR。开发依赖不受此限制。
- 使用的 DSH 服务只有 `webServer` 和 `connection`，写在 `inject` 里（单元测试会检查）。不允许访问其他私有字段。
- **所有对 DSH 的调用都必须放在 `src/dsh/` 适配层里**，其他模块只能调用适配层，不能直接访问 DSH。支持的 DSH 版本范围只在 `src/dsh/versions.js` 中定义，`package.json` 的 `engines.dsh` 必须和它一致（单元测试会检查）。这是为以后的「DSH 新版本自动适配」预留的设计，见 `docs/roadmap.md`。
- 测试代码（`test/` 下）可以直接调用 DSH 的接口，用来做验证；插件代码不可以。
- DSH 的端口每次启动都可能变化，**每个请求都要实时读取** `ctx.webServer.port`，不要缓存。
- 插件的生命周期用 `ctx.effect()` 管理。加载时不要阻塞等待 DSH 启动，在后台轮询即可。
- **日志**：使用 `ctx.logger`，并且带上 `[dshremote]` 前缀。`dsh web` 默认不输出插件的 info 日志，所以排查问题时不能指望日志；需要让用户看到的状态，要显示在本机配对页上。
- 状态文件放在 `<DSH_HOME>/dshremote/` 下。设备 token 只存哈希。
- 插件代码要能同时在 Windows 和 Linux 上运行：路径一律用 `path.join` 拼接；启动子进程时，用 `process.execPath` 运行 JS 入口，不要依赖 shell 或 `.cmd` 包装。

## 安装方式

- **官方 Desktop**：先在插件目录下运行 `npm install --omit=dev`，再在 Desktop 中打开「插件 → 安装第三方插件」，填写插件目录的绝对路径。npm 版的 `dsh` 命令不允许管理 `desktop` profile。
- **`dsh web`**：`dsh plugin --profile web add -w <插件目录的绝对路径>`

## 可复用的参考实现

之前写过的 `dsh-tailnet-remote`（Tailscale 版）已经在 DSH 0.2.2-rc.1 上验证过下面这些做法：读取 DSH 端口、用 `authenticatedUrl()` 换取 DSH cookie、把 Host / Origin 改写为回环地址后转发、转发 WebSocket、本机页面的访问控制。DSH 接口的实测结论见 `docs/research/dsh-session-api.md`。
