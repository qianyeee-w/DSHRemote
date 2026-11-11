# 创建／导入项目

- 状态：已实现，Windows 自动化检查和 Android 自动化测试通过；Windows Desktop 手动流程与 Linux 环境验收待完成。
- 范围：目录选择、新建单个项目目录、登记已有目录、在选定工作区开始会话；优先于 M3 剩余任务、完整文件管理与终端。
- 决策：ADR 0013；接口以 protocol.md「创建／导入项目」为准。

## 实现

- `plugin/src/directories.js`：Node 标准库目录操作，支持 Windows／Linux 本机绝对路径，校验名称、解析链接、限制列表数量与扫描量；不执行 shell，不读写文件内容。
- `plugin/src/dsh/facade.js`：目录异常转换、通过公开 `workspace/create` 登记工作区，精简响应。
- `plugin/src/server/routes.js`：三个必须认证的白名单操作。
- Android `data/api/`：目录和登记的数据结构、接口与错误码。
- Android `ui/sessions/ProjectFlow.kt`、`ProjectPicker.kt`：在新建会话中进入目录选择，创建／导入后明确点击「开始会话」。进程存活时跨界面重建保留操作状态。

## 恢复规则

- 名称冲突时不覆盖，不自动导入。
- 创建成功、登记失败：保留目录，按钮只重试登记。
- 创建回执丢失或返回结果不可确认：停止重复创建，刷新后由用户选择已有目录导入。
- 导入／登记按实际路径重试，由官方接口复用工作区。
- 切换页面或 App 进程退出后，仍可浏览目录并重新导入，不在后台自动补写操作。

## 验证

- 插件 `npm run check`：含名称／路径反向用例、链接、列表上限、请求认证、撤销、部分失败；已运行，后续修改后复验。
- 插件 `npm run contract`、`npm run e2e`：真实基线 DSH 中验证创建／导入、重复登记、会话归属、重启保留工作区和撤销。
- Android `test lint ktlintCheck assembleDebug`：含 ProjectFlow 的重复点击、登记重试、创建回执丢失、旧插件、证书和撤销状态。
- Android `devServerTest`：真实 HTTPS 插件接口创建目录、登记、重复登记及新建会话。
- `connectedDebugAndroidTest`：新目录选择界面及现有界面回归；手机已有不同签名的 `.debug` 包时，使用 `.projecttest` 隔离后缀，保留原应用和配对数据。
- 实际流程：Windows Desktop 与 Linux `dsh web`，在手机上分别创建和导入包含中文／空格的目录，确认 Desktop 可见，并从工作区开始会话。

## 当前执行结果（2026-11-11）

- Windows：`npm run check` 通过（238 项通过、2 项跳过），含新增目录操作与认证反向用例。
- Windows 真实 DSH `0.2.2-rc.1`：`npm run contract` 全部通过，包括创建目录、幂等登记、会话实际工作目录及不存在目录拒绝。
- Windows 真实 DSH + 插件 HTTPS：`npm run e2e` 全部通过，包括中文／空格／`#` 路径、名称冲突、重复导入、会话归属、重启保留和撤销后拒绝三项新操作。
- Android：`test lint ktlintCheck assembleDebug` 全部通过，JVM 实际运行 248 项测试，0 失败；`devServerTest` 的 20 项真实插件联调测试通过。
- Windows 测试兼容：JVM 单元测试改用同版本 DataStore 的 JVM 后端，修复 Android mockable 后端在 Windows 上不能覆盖文件的问题；APK 仍使用 Android 后端。明文 HTTP 探测测试等待并排空 TLS 客户端输入，避免 Windows 的连接重置吞掉响应；安全断言保持不变。
- 真机：小米 Android 17 的 `connectedDebugAndroidTest` 通过，60 项测试、0 失败，覆盖 TLS／Keystore 和新增界面。现有 `.debug` 来自另一台电脑、签名不同；通过 `.projecttest` 隔离包名运行，没有覆盖或卸载原 App。
- Linux：本机 WSL 启动报 `HCS_E_SERVICE_NOT_AVAILABLE`，未改动虚拟化设置；Linux 插件测试留给 GitHub Actions。Windows Desktop 图形界面与手机端创建／导入的完整手动流程尚未重新验收，当前真实后端验证使用 Windows `dsh web`。

## 与 Android 视觉改版的集成

首页工作区分组、项目选择和新建会话入口已与视觉改版合并；保留创建成功后显式点击「开始会话」以及失败恢复规则。整体验证结果见 [`android-visual-refresh.md`](android-visual-refresh.md)。

未实际执行的项目不视为通过。
