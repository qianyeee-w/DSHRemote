# 项目现状与交接

> 给新加入的 agent：读完根目录的 `AGENTS.md` 后读这一份。它记录项目做到了哪里、我们怎么协作，以及开发中踩过的坑。每完成一个阶段或做出重要决定，都要更新这份文件。

最后更新：2026-11-09（M1 完成，M2 方案已确认）

## 1. 进度

| 阶段 | 状态 | 文档 |
|---|---|---|
| 需求与设计（spec、protocol、security、ADR 0001–0008） | 完成 | `docs/` |
| DSH 接口调研 | 完成 | `docs/research/dsh-session-api.md` |
| **M1 电脑端插件** | **完成**（T1–T10，Windows 官方 Desktop 已真机验证） | `docs/plans/m1-plugin.md`（第 6 节是验收结果和遗留问题） |
| **M2 Android App** | **进行中**：T1（插件 dev-server）、T2（Android 骨架 + CI）、T3（证书校验层）完成，下一步 T4（主机与凭据存储） | `docs/plans/m2-android.md` |

**下一步**：按 `docs/plans/m2-android.md` 第 5 节的顺序做 T1–T12，每完成一项汇报一次。

M2 开发要知道：

- `android/` 的骨架已建立（T2）：Gradle 版本目录、lint（警告即错误）、ktlint、禁止明文流量的配置及其单元测试、CI（`android.yml`）。命令和约定见 `android/AGENTS.md`。
- **开发 App 时连接 dev-server**（`cd plugin && npm run dev-server`，T1 完成）：真实的 `dsh web` + 本插件 + 假模型，消息里的关键词决定模型的行为（ASK 提问、SHELL 审批、STREAM 流式、THINK 思考、SLOW 长输出）。默认局域网端口 3290、配对页 3291，**不用 3190 / 3191**：用户的开发服务器上，3190 被用户自己的 `dsh web` 服务占用。数据目录 `~/.cache/dshremote-dev` 保存指纹和已配对设备，重启后手机不需要重新配对。`npm run dev-server -- pair` 打印配对二维码，`restart` 重启 DSH，`revoke` 撤销设备。测试代码一律用 `dsh-runtime.mjs` 的 `freePort()` 找空闲端口。
- **证书校验层**在 `android/.../data/net/`（T3）：App 的所有请求都必须用 `ClientFactory.forPin(指纹)` 创建的客户端；手动配对用 `PairingProbe` 取指纹。它的测试放在 `app/src/sharedTest/`，同一组测试在 JVM 和真机上各跑一遍；改动这一层后必须在真机上跑 `connectedDebugAndroidTest`。
- **测试真机**：用户提供一台 Android 17 的小米手机，通过无线调试连接开发机（用法见 `android/AGENTS.md`「真机」）。手机和开发机在同一局域网，已确认手机能连到 dev-server 的 3290 端口。
- 插件对外的接口以 `docs/protocol.md` 为准，已经按实现定稿，没有待定项。App 只能使用其中列出的接口。
- App 端的安全要求在根目录 `AGENTS.md` 的安全红线和 `android/AGENTS.md` 中，尤其是：证书指纹不匹配时直接拒绝，**不提供「仍然继续」**。

### 待办（插件）

- **端口被占用时，用户看不到任何提示。** 插件的局域网端口或配对页端口被其他程序占用时，插件会启动失败，但 DSH 仍然显示插件为 active，也不打印错误。T10 在 Windows 上遇到过一次（VS Code 端口转发），交接时在 Linux 服务器上又遇到一次。`npm run load-check` 现在会直接指出端口被占用，但官方 Desktop 的用户仍然什么都看不到。
  - 建议的改法：局域网端口启动失败时，配对页照常启动，并在页面上显示「端口 X 被占用」以及处理方法。配对页端口也被占用时，至少要让插件进入失败状态，不要显示为 active（需要先确认 DSH 怎么判断插件失败）。
  - 改之前先和用户确认方案。

## 2. 协作方式（用户的习惯）

- 全程用**中文**交流，文档、注释、提交信息也用中文。
- **一次只做一个任务。** 每个任务做完后，向用户汇报，等用户回复「继续」再做下一个。
- **较大的改动先给方案**（改哪些文件、怎么测试），用户确认后再动手。需求不清楚时先问，不要自行扩大范围（以 `spec-v1.md` 为准）。
- **直接推送到 `main`**，不开 PR。所以推送前必须在本地跑完相关测试（见 `plugin/AGENTS.md`、`android/AGENTS.md` 的「提交前」）。推送后用 `gh` 查看 CI，全部通过后再汇报。
- 提交信息：`<type>(<scope>): <中文摘要>`。正文写清楚做了什么、为什么。
- **汇报的写法**：先说结果，再说做了什么、发现了什么问题、有什么限制，最后说下一步做什么并询问是否继续。没有实际跑通的测试，不要说已经完成。
- 需要用户在真机上操作时（例如 Windows 上的 Desktop、手机），写成一份步骤清单，或者一段给用户本地 agent 的提示词，并说明哪些步骤要用户亲自做。参考 `docs/plans/m1-windows-verification.md`。

## 3. 开发中学到的经验

DSH 接口的细节都记录在 `docs/research/dsh-session-api.md` 第 5 节（5.6–5.8 是实现 M1 时确认的）。下面是最容易踩的几条：

- **「看起来对」的测试不够，要在真实环境里测。** M1 中以下三个问题，单元测试都没有发现：
  - DSH 0.2.2-rc.1 的源码里有按 `requestId` 去重的逻辑，实测却不去重。是契约测试发现的。
  - 插件同时注册两个事件监听者，打开了两条 `$events` 流，导致回答提问被误判为 409。是端到端测试发现的。
  - 配对页的 `Referrer-Policy: no-referrer` 让浏览器提交表单时发送 `Origin: null`，结果无法生成配对码。是 Windows 真机验证发现的，单元测试用 Node 客户端手动设置了 Origin，所以测不出来。

  所以涉及 DSH 行为的结论，要用契约测试（真实的 `dsh web`）验证；涉及浏览器的，要用真实的浏览器验证（`npm run browser-check`）；涉及手机的，要在真机上验证。
- **DSH 的多客户端行为**：
  - 每条 `$events` 流都算一个独立的客户端。
  - 一个客户端回答了审批，其他客户端会收到 `cancel`。
  - 已经被处理掉的事件，再回答时 DSH 静默返回成功。所以插件靠 `cancel` 帧来判断 409。
- **Windows 上的实际情况**：
  - 用 VS Code Remote-SSH 时，VS Code 会把服务器上监听的端口自动转发到 Windows 本机，可能占用插件的端口。
  - 装了 VMware、VirtualBox、WSL 的电脑，会有很多虚拟网卡。
  - 防火墙放行时只勾「专用网络」。
- **Android 构建和 TLS**：
  - AGP 9 内置了 Kotlin 支持。给 source set 加目录要用 `kotlin.directories.add(...)`；放在 `java.srcDir(...)` 里的 `.kt` 文件不会被编译，测试会**静默地不运行**（构建照样成功）。加测试后要看一眼测试报告里的数量。
  - 手机上的 TLS 实现（Conscrypt）和电脑上的 JDK 有差别。例如握手失败后，Conscrypt 的 session 仍然带着对端证书，JDK 的不带。所以 TLS 相关的测试必须在真机上也跑一遍。
- **DSH 源码**：做接口相关的工作时，需要查看 DSH 的源码。仓库是 `https://github.com/deepseek-ai/deepseek-harness`，标签 `dsh-v0.2.2-rc.1`。可以克隆到项目外的目录中参考，不要放进本仓库。社区项目 dsh-Go 是 AGPL 许可证，**只能看接口名，不能复制代码**。

## 4. 测试命令速查

插件（在 `plugin/` 下，详见 `plugin/AGENTS.md`）：

| 命令 | 用途 |
|---|---|
| `npm run check` | lint + 类型检查 + 单元测试，提交前必须通过 |
| `npm run load-check` | 装进真实的 `dsh web`，确认插件能加载、能连上 DSH |
| `npm run contract` | 契约测试：假模型驱动真实的 `dsh web`，逐项验证插件依赖的 DSH 接口 |
| `npm run e2e` | 端到端测试：模拟手机走完整个流程 |
| `npm run browser-check` | 用真实的浏览器操作配对页 |
| `node scripts/verify.mjs` | 真机验证（见 `docs/plans/m1-windows-verification.md`） |

`load-check`、`contract`、`e2e` 需要 PATH 上有 pnpm，第一次运行时会自动下载 DSH。设置 `DSH_INSTALL_DIR=<目录>` 可以复用已经下载的 DSH。
