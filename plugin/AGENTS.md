# plugin/ — 电脑端插件

DSH 插件（Node，ESM，纯 JavaScript），装在官方 Desktop 或 `dsh web` 上。先读根目录的 `AGENTS.md`。

> 状态：骨架还没建立，M1 开始时补齐。下面的命令和目录是计划中的约定，建立骨架时按此执行；如有调整，同步更新本文件。

## 命令

```sh
cd plugin
npm install
npm test          # 单元测试：node --test
npm run lint
npm run e2e       # 端到端测试：用真实的 DSH 0.2.2-rc.1，tailscale 等外部命令用假程序代替
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
│   ├── dsh-session.js  # 持有 DSH 官方会话、转发请求
│   └── admin-page.js   # 本机配对页（只监听 127.0.0.1）
└── test/
```

## 约定

- 使用的 DSH 服务只有 `webServer` 和 `connection`，写在 `inject` 里。不允许访问其他私有字段。
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
