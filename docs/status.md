# 项目现状与交接

> 给新加入的 agent：读完根目录的 `AGENTS.md` 后读这一份。它记录项目做到了哪里、我们怎么协作，以及开发中踩过的坑。每完成一个阶段或做出重要决定，都要更新这份文件。

最后更新：2026-11-08（M1 完成，M2 尚未开始）

## 1. 进度

| 阶段 | 状态 | 文档 |
|---|---|---|
| 需求与设计（spec、protocol、security、ADR 0001–0008） | 完成 | `docs/` |
| DSH 接口调研 | 完成 | `docs/research/dsh-session-api.md` |
| **M1 电脑端插件** | **完成**（T1–T10，Windows 官方 Desktop 已真机验证） | `docs/plans/m1-plugin.md`（第 6 节是验收结果和遗留问题） |
| **M2 Android App** | **未开始** | 先写 `docs/plans/m2-android.md` |

**下一步**：写 M2 的实施方案 `docs/plans/m2-android.md`，格式参考 `m1-plugin.md`：目标、目录、任务拆分（T1、T2…，每个任务都有验收标准）、测试方法、验收清单。**方案经用户确认后才开始写代码。**

M2 开工前要知道：

- `android/` 目前只有 `AGENTS.md`，里面是计划中的技术选型和约定，骨架还没建立。
- `android/AGENTS.md` 提到「插件的 mock server」，**它还不存在**。现有的可复用部件：
  - `plugin/test/helpers/dsh-runtime.mjs`：安装并启动真实的 `dsh web`，可以装入插件；
  - `plugin/test/contract/fake-llm.mjs`：假模型服务，用关键词驱动 Agent，比如 ASK 触发提问、SHELL 触发审批、STREAM 慢速流式输出。

  把这两个组合起来，就是一个给 App 开发用的「真实插件 + 假模型」服务器。建议作为 M2 的第一个任务，先把它做成命令，例如 `npm run dev-server`。
- 插件对外的接口以 `docs/protocol.md` 为准，已经按实现定稿，没有待定项。App 只能使用其中列出的接口。
- App 端的安全要求在根目录 `AGENTS.md` 的安全红线和 `android/AGENTS.md` 中，尤其是：证书指纹不匹配时直接拒绝，**不提供「仍然继续」**。

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
