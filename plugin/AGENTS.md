# plugin/ — 电脑端插件

DSH 插件（Node，ESM，纯 JavaScript），装在官方 Desktop 或 `dsh web` 上。先读根目录的 `AGENTS.md`。

> 状态：骨架还没建立，M1 开始时补齐。下面的命令和目录是计划中的约定，建立骨架时按此执行；如有调整，同步更新本文件。

## 命令

```sh
cd plugin
npm install
npm test          # 单元测试：node --test
npm run lint
npm run contract  # DSH 接口契约测试；DSH_VERSION=0.2.3 npm run contract 可测试其他版本
npm run e2e       # 端到端测试：用真实的 DSH 0.2.2-rc.1
```

## 目录

```
plugin/
├── index.js            # DSH 插件入口：name / inject / apply
├── cordis.patch.yml    # 插件挂载与默认配置
├── src/
│   ├── config.js       # 配置校验（非法值直接报错）
│   ├── tls.js          # 证书生成、指纹计算
│   ├── pairing.js      # 配对码、设备 token、设备存储
│   ├── server.js       # 局域网 HTTPS / WSS 服务
│   ├── dsh/            # DSH 适配层：所有对 DSH 的调用只能写在这里
│   │   ├── versions.js # 支持的 DSH 版本范围（唯一定义处）与启动时的版本检查
│   │   └── ...         # 持有 DSH 官方会话、转发或封装 DSH 接口
│   └── admin-page.js   # 本机配对页（只监听 127.0.0.1）
└── test/
    ├── unit/
    └── contract/       # DSH 接口契约测试：DSH_VERSION 指定版本，输出 JSON 结果
```

## 约定

- 使用的 DSH 服务只有 `webServer` 和 `connection`，写在 `inject` 里。不允许访问其他私有字段。
- **所有对 DSH 的调用都必须放在 `src/dsh/` 适配层里**，其他模块只能调用适配层，不能直接访问 DSH。支持的 DSH 版本范围只在 `src/dsh/versions.js` 中定义一次。这是为以后的「DSH 新版本自动适配」预留的设计，见 `docs/roadmap.md`。
- 契约测试（`test/contract/`）只测试插件实际用到的 DSH 接口。通过 `DSH_VERSION` 环境变量选择要测试的 DSH 版本，测试结果输出为机器可读的 JSON。
- DSH 的端口每次启动都可能变化，**每个请求都要实时读取** `ctx.webServer.port`，不要缓存。
- 插件的生命周期用 `ctx.effect()` 管理。插件加载时不要阻塞等待 DSH 启动，在后台轮询即可。
- 安装方式：Desktop 用「插件 → 安装第三方插件」，填本地绝对路径。npm 版的 `dsh` 命令不允许管理 `desktop` profile。
- 不引入非必要的依赖。确实需要时，优先选择体积小、没有子依赖的库，或者直接放进 `src/vendor/` 并保留原许可证声明。
- 状态文件放在 `<DSH_HOME>/dshremote/` 下。设备 token 只存哈希。

## 可复用的参考实现

之前写过的 `dsh-tailnet-remote`（Tailscale 版）已经在 DSH 0.2.2-rc.1 上验证过下面这些做法，可以直接参考：

- 读取 DSH 端口
- 用 `authenticatedUrl()` 换取 DSH cookie
- 把 Host / Origin 改写为回环地址后转发（这样能通过 DSH 的 Host 校验）
- 转发 WebSocket
- 本机页面的访问控制
