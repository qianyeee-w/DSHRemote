# M3 方案：v1 发布

- 状态：已确认（2026-11-11）
- 日期：2026-11-11
- 范围：让 v1 能被正常安装和长期使用：正式签名的 App、插件安装包、发布流程、用户文档，以及 DSH 新版本的兼容性定时检测。**不加新功能**（spec「第二版」的功能都不在 M3）。
- 使用对象：目前只有用户自己使用，仓库保持私有；以后设计成熟了可能开源，所以文档按「给别人看」的标准写。界面和文档只有中文。
- 完成标志：本文第 5 节的验收清单全部通过。

## 1. 已确定的选择

| 项目 | 选择 | 原因 |
|---|---|---|
| 发布渠道 | 本仓库的 GitHub Releases（私有仓库，只有自己能下载） | 不依赖第三方；以后公开仓库时不用改流程 |
| App 签名 | 正式发布密钥保存在 GitHub Secrets，打标签后由 CI 构建并签名 | 不需要在 Windows 上搭 Android 构建环境；密钥另外由用户离线备份 |
| 版本号 | App 和插件共用一个版本号，一个标签 `vX.Y.Z` 同时发布两者 | 只有一个人用，分开发布没有好处；协议的兼容性仍由 `protocol.md` 的版本协商保证 |
| R8（代码压缩和混淆） | **不开启** | 开启后可能破坏依赖反射的代码（序列化等），需要额外的 keep 规则和完整的真机回归；v1 自己用，包大一些没有关系。以后要开启时单独评估 |
| debug 包 | applicationId 加后缀 `.debug`，名称为「DSHRemote 调试」 | release 包和 debug 包可以同时装在手机上，互不覆盖 |
| 定时检测 | 每天一次，只在 Linux 上跑；手动触发时可以选 Windows | 私有仓库的 Windows runner 按 2 倍计时；DSH 的接口和平台无关，平台相关的问题由推送时的 CI 覆盖 |

需要记录的决策写成 ADR 0012「v1 的发布方式」：发布渠道、CI 签名、不开 R8、版本号规则。

## 2. 任务

一次只做一个任务，按编号顺序做，每个任务完成后汇报。文档（T6）放在功能都完成之后，截图里就不会有「#」等问题。

### T1 DSH 新版本的兼容性定时检测

`roadmap.md` 第 1 节的前两步：定时检测并自动开 issue。agent 适配仍由人手动发起。

**做什么**

- 新的工作流 `.github/workflows/dsh-compat.yml`：
  - 每天北京时间 9:00（`cron: '0 1 * * *'`）运行，也可以手动触发。手动触发时可以指定 DSH 版本和平台。
  - 第一步：用 `npm view @deepseek-ai/dsh dist-tags` 取 `latest`、`next`、`alpha` 指向的版本，留下**比基线新**的版本（去重）。没有就结束。
  - 第二步：对每个版本跑 `load-check`、`contract`、`e2e`（`DSH_VERSION=<版本>`）。
  - 第三步：失败时开 issue，标题「DSH <版本> 兼容性检测失败」。同一个版本只开一次（已有同名 issue 时不再开，关闭了的也算）。
    - issue 正文包括：版本号、发布时间、是否在 `SUPPORTED_DSH_RANGE` 之内、每项检测的结果（三个检测脚本最后一行 JSON 中的 `name`、`ok`、`detail`，截断）、DSH 升级指南的链接、这次运行的链接。
    - 正文只用检测结果中的字段，这些字段本来就不含 token 等秘密（安全红线 5）。另外再兜底：32 个字符以上的 base64url 串一律替换为「[已隐去]」。
  - 通过时不开 issue。同一个版本、同一份插件代码通过后用缓存记下来，之后不再重复测试。
  - 定时运行时，**已经开过 issue 的版本也跳过**，否则同一个失败会每天重跑、每天发失败通知邮件。手动触发总是检测。
- 版本选择、issue 内容的生成逻辑放在 `plugin/scripts/dsh-compat-lib.mjs`（纯函数，有单元测试），命令行入口是 `plugin/scripts/dsh-compat.mjs`。工作流只负责调用。
- `load-check` 的输出增加 `results` 数组，和 `contract`、`e2e` 的格式一致，三项检测用同一种方式解析。
- 工作流的权限：默认 `contents: read`；选择版本的 job 加 `issues: read`，开 issue 的 job 加 `issues: write`。

**怎么测试**

- 单元测试：版本选择（比基线新、去重、预发布版本的排序、解析失败的版本号）、issue 标题和正文（截断、只含允许的字段）。
- 手动触发两次：
  1. 指定基线版本：应该通过，不开 issue；
  2. 指定一个较旧的版本（例如 `0.2.1-rc.5`，不在支持范围内）：应该失败并开出 issue，内容正确。重复触发时不再开第二个。验证完后关闭这个 issue。

### T2 release 构建和签名

**做什么**

- `android/app/build.gradle.kts`：
  - release 的签名配置从环境变量读取：`DSHREMOTE_KEYSTORE`（文件路径）、`DSHREMOTE_KEYSTORE_PASSWORD`、`DSHREMOTE_KEY_ALIAS`。没有这些变量时，`assembleRelease` 直接失败并说明原因，不会悄悄生成未签名或用调试密钥签名的包。
  - `versionName` 和 `versionCode` 从版本号生成：`versionCode = X * 10000 + Y * 100 + Z`。版本号只在一个地方定义（`android/gradle.properties` 的 `dshremote.version`），发布时由 CI 检查它和标签一致。
  - debug 包：`applicationIdSuffix = ".debug"`，应用名「DSHRemote 调试」。
- 密钥由**用户自己生成**（PKCS12，RSA 4096，有效期 30 年）：用户在自己打开的终端里（例如 SSH 到开发服务器，那里已有 `keytool` 和 `gh`）运行脚本，不经过 agent。密码只在这个终端里输入，不出现在对话、仓库和日志中。步骤写在 `docs/release.md`（T6）里，并提供脚本 `android/scripts/create-release-key.sh`：
  1. 用 `keytool` 生成密钥，密码由 keytool 交互输入；
  2. 用 `gh secret set` 上传三个 Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`（交互输入）、`ANDROID_KEY_ALIAS`；
  3. 打印证书的 SHA-256 指纹（公开信息），写进仓库的 `android/release-cert.sha256`；
  4. 提醒用户把密钥文件和密码备份到密码管理器，然后删除本地的密钥文件。
- `.gitignore` 增加 `*.p12`、`*.jks`、`*.keystore`（已有 `*.pem`、`*.key`）。

**怎么测试**

- 单元测试或 Gradle 检查：版本号到 `versionCode` 的换算；缺少签名环境变量时 `assembleRelease` 失败。
- 本地用一个临时生成的测试密钥（只放在 scratchpad，用完删除）跑 `assembleRelease`，用 `apksigner verify --print-certs` 确认签名正确、`debuggable` 为 false。
- 真机：装上 debug 包（新的 `.debug` 包名）后跑 `connectedDebugAndroidTest`，确认改包名没有破坏测试。

### T3 插件安装包

**做什么**

- 先确认官方 Desktop「安装第三方插件」的行为：是引用原目录还是复制一份；会不会自己安装依赖（npm 版的 `dsh` 安装插件时会调用 pnpm）。结论记录在 `docs/research/`。
- 根据结论，脚本 `plugin/scripts/pack.mjs` 生成 `dshremote-plugin-X.Y.Z.zip`：内容是 `package.json` 中 `files` 列出的文件、`LICENSE`、`README`。需要时再加上只含运行时依赖的 `node_modules`（`ws`），不包含测试和开发依赖。
- 插件的数据（证书、已配对设备、`state.json`）存放在 `~/.dsh/` 下，和插件目录无关。所以升级插件只要用新版本覆盖插件目录，配对不会丢失。这一点要实际验证。

**怎么测试**

- 自动：解压安装包，用 `dsh plugin add` 装进真实的 `dsh web`，跑 load-check。加进 `plugin.yml`，Linux 和 Windows 都跑。
- 自动：安装包里不包含 `test/`、`scripts/`、开发依赖、`*.pem`、`*.key`。
- 用户在 Windows 上验证（T7）：用安装包安装和升级插件。

### T4 发布工作流

**做什么**

- 新的工作流 `.github/workflows/release.yml`，推送 `v*` 标签时运行：
  1. 检查标签和 `dshremote.version`、`plugin/package.json` 的版本一致；
  2. 跑一遍 android 和 plugin 的检查（复用现有的命令）；
  3. 从 Secrets 还原密钥到 runner 的临时目录，`assembleRelease`，然后删除密钥文件；
  4. 用 `apksigner` 检查 APK 的签名证书指纹和 `android/release-cert.sha256` 一致，不一致就失败（防止 Secrets 被换成别的密钥）；
  5. 打插件安装包（T3）；
  6. 创建 GitHub Release，上传 `dshremote-X.Y.Z.apk`、`dshremote-plugin-X.Y.Z.zip` 和两者的 SHA-256 校验文件；更新说明取自 `CHANGELOG.md` 中对应版本的一节。
- 新增 `CHANGELOG.md`，从 `0.1.0` 开始记录。
- 工作流的权限：只在创建 Release 的步骤需要 `contents: write`。

**怎么测试**

- 先推一个预发布标签（例如 `v0.1.0-rc.1`），Release 标为预发布。确认：
  - 产物齐全，校验和正确；
  - 日志中没有密钥和密码；
  - 下载的 APK 能装到手机上（见 T7）。
- 反向用例：在测试分支上把 `release-cert.sha256` 改成错误的值，确认工作流失败。

### T5 修复会话标题中的「#」

**做什么**

DSH 用第一条消息生成会话标题，消息以 Markdown 标题开头时，标题会带上「#」。在 App 显示会话标题的地方去掉开头的 Markdown 标记（`#`、`>`、`-`、`*` 等）。只改显示，不改协议，插件原样转发。

**怎么测试**

- JVM 单元测试：各种前缀、只有符号、正文中间的「#」不受影响。

### T6 用户文档

**做什么**

- `README.md`：去掉「还不能使用」，改成这个项目是什么、截图、快速开始（下载、安装插件、安装 App、扫码配对）、链接到详细文档。
- 新增 `docs/user-guide.md`（用户手册）：
  - 安装和升级（插件、App），防火墙只允许专用网络；
  - 配对、手动输入地址、核对指纹；
  - 日常使用：会话、审批和提问、通知；
  - 在外面使用（Tailscale 等，只说明思路，不写具体配置）；
  - 安全：指纹不一致时怎么办、手机丢了怎么撤销；
  - 常见问题：端口被占用（插件显示失败）、收不到通知（电池优化、小米的省电设置和自启动）、DSH 升级后插件不兼容、连不上。
- 新增 `docs/release.md`（给维护者）：生成和备份密钥、发版步骤、兼容性检测的 issue 怎么处理。
- 截图在真机上用 debug 包和 dev-server 截取（只截本 App，不截通知栏），不包含指纹、配对码等真实信息。

**怎么测试**

- 文档里的命令都实际运行一遍。
- T7 中由用户按用户手册从头安装一遍，文档中说不清楚的地方当场修改。

### T7 release 包的真机验证

**做什么**

用户按 `docs/user-guide.md` 从头操作一遍。debug 包里原有的配对不受影响；release 包需要重新扫码配对。

- 卸载旧的 debug 包（它的包名和 release 包相同，签名不同，不卸载就装不上 release 包）。
- Windows 官方 Desktop 上：用安装包安装插件，扫码配对 release 包，按 `m2-device-verification.md` 第 6 节走一遍验收场景 1–7。
- 端口被占用时，Desktop 的插件列表显示失败（插件遗留问题 1 的最后一项）。
- **不关闭电池优化**时锁屏 30 分钟以上，看能不能收到审批通知（M2 未验证的一项）。只记录结果，App 中现有的引导不改。
- 升级：发布 `v0.1.0` 正式版后，用它覆盖安装 rc 版的 App 和插件，确认配对保留。

结果记录在 `docs/plans/m3-release-verification.md`。

## 3. 安全

- 发布密钥和密码只存在于 GitHub Secrets 和用户的离线备份中，不进入仓库、对话和日志。CI 中的密钥文件放在 runner 的临时目录，用完删除。
- 发布工作流检查 APK 的签名证书指纹，防止 Secrets 被替换后发出用其他密钥签名的包。
- release 包 `debuggable=false`；网络安全配置不变（禁止明文流量）。
- 兼容性检测开出的 issue 只包含检测结果中的字段，不包含 token、配对码、私钥。

## 4. 不在 M3 范围内

- spec「第二版」的全部功能，包括在通知里直接审批。
- 宽表格单元格被截断成「…」。
- App 进程被杀后，旧通知点开只进入首页。
- 开启 R8、上架应用商店、App 内检查更新。
- 公开仓库、英文文档和界面。
- 兼容性检测失败后由 agent 自动适配（`roadmap.md` 第 1 节的第 3 步以后）。

## 5. 验收清单

- [ ] 每天的兼容性检测在运行；手动指定一个不兼容的版本时开出 issue，同一个版本不重复开
- [ ] 推送标签后，Release 上有签名的 APK、插件安装包和校验和；签名证书指纹检查的反向用例失败
- [ ] release 包和 debug 包可以同时安装在手机上
- [ ] 插件安装包在 Linux 的 `dsh web` 和 Windows 的官方 Desktop 上都能安装；覆盖升级后配对保留
- [ ] `README.md`、`docs/user-guide.md`、`docs/release.md` 完成，用户按用户手册从头安装成功
- [ ] 会话标题不再出现「#」
- [ ] 在 Windows 官方 Desktop 上，用 release 包走通 `spec-v1.md` 的验收场景 1–7
- [ ] 记录了不关闭电池优化时的通知表现
- [ ] 发布 `v0.1.0`
