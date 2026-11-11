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
  - `versionName` 和 `versionCode` 从版本号生成：`versionCode = X * 1000000 + Y * 10000 + Z * 100 + N`（`X.Y.Z-rc.N`，正式版 N 取 99，这样 T4 的预发布标签 `v0.1.0-rc.1` 也有正确的顺序）。版本号只在一个地方定义（`android/gradle.properties` 的 `dshremote.version`），发布时由 CI 检查它和标签一致。
  - 签名检查只挂在打包任务（`packageRelease`、`packageReleaseBundle`）上，`test`、`lint` 不需要签名信息。
  - debug 包：`applicationIdSuffix = ".debug"`，`versionNameSuffix = "-debug"`（配对页的设备列表能看出是哪个包），应用名「DSHRemote 调试」（`src/debug/res`）。
  - 决策记录在 ADR 0012。
- 密钥由**用户自己生成**（PKCS12，RSA 4096，有效期 30 年）：用户在自己打开的终端里（例如 SSH 到开发服务器，那里已有 `keytool` 和 `gh`）运行脚本，不经过 agent。密码只在这个终端里输入，不出现在对话、仓库和日志中。步骤写在 `docs/release.md`（T6）里，并提供脚本 `android/scripts/create-release-key.sh`：
  1. 脚本用 `read -s` 读取密码（输两次，至少 12 个字符），通过环境变量交给 `keytool`（`-storepass:env`），不出现在命令行参数中；
  2. 用 `gh secret set` 从标准输入上传三个 Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`。仓库里已有发布密钥的 Secrets 时拒绝运行，防止换掉密钥；
  3. 打印证书的 SHA-256 指纹（公开信息），写进仓库的 `android/release-cert.sha256`；
  4. 提醒用户把密钥文件和密码备份到密码管理器，然后删除本地的密钥文件。
- `.gitignore` 增加 `*.p12`（已有 `*.jks`、`*.keystore`、`*.pem`、`*.key`）。

**怎么测试**

- JVM 单元测试 `VersionTest`：用独立的实现检查 versionCode 的公式和顺序，debug 包的包名和版本后缀。AGP 9 默认只跑 debug 变体的单元测试，release 包由 `aapt2 dump badging` 检查。
- 反向用例（手动跑 Gradle）：版本号格式不对（`0.1`、`0.100.0`、`0.1.0-rc.99`、`0.1.0-beta.1`、`01.1.0`）时构建失败；缺少签名环境变量时 `assembleRelease` 失败。
- 生成密钥的脚本：用一个假的 `gh`（只记录 Secret 的名称和长度）在 scratchpad 里跑通，并验证四个反向用例：已有 Secrets、两次密码不一致、密码太短、密钥文件已存在。
- 本地用一个临时生成的测试密钥（只放在 scratchpad，用完删除）跑 `assembleRelease`，用 `apksigner verify --print-certs` 确认签名正确、`debuggable` 为 false。
- 真机：装上 debug 包（新的 `.debug` 包名）后跑 `connectedDebugAndroidTest`，确认改包名没有破坏测试。

### T3 插件安装包

**做什么**

- 先确认官方 Desktop「添加插件」的行为：是引用原目录还是复制一份，会不会自己安装依赖。结论记录在 [`docs/research/dsh-plugin-install.md`](../research/dsh-plugin-install.md)：
  - 「添加插件」接受包名、Git 地址、本地目录和本地 `.tgz` 的绝对路径，内部运行 `pnpm add`；
  - **本地目录只做链接，不安装依赖**；**`.tgz` 会复制进 profile**，依赖从 npm 源下载，已打包在 `.tgz` 中的依赖直接使用；
  - 两种方式下原文件都**不能删除或移动**，否则这个 profile 以后的 pnpm 操作（包括安装其他插件）都会失败；
  - 直接安装新版本的 `.tgz`，或者先卸载再安装，都能升级。插件数据（`<DSH_HOME>/dshremote/`）和 profile 中的插件配置都保留，配对不丢。
- 所以安装包改用 **`.tgz`**，不用原计划的 zip：脚本 `plugin/scripts/pack.mjs`（`npm run pack`）生成 `plugin/dist/dshremote-plugin-X.Y.Z.tgz`。
  - 内容：`package.json` 中 `files` 列出的文件（含 `LICENSE`）、`README.md`、打包的运行时依赖 `ws`（`bundleDependencies`，安装时不需要联网）；
  - 包里的 `package.json` 去掉开发依赖和脚本；
  - 打包后检查包里的文件（`scripts/pack-lib.mjs`），不通过时删除安装包并失败。
- 新增 `plugin/README.md`：DSH 的插件详情页会显示它。内容包括安装、升级、配置和数据位置，并提醒用户 `.tgz` 不能删除。
- `test/helpers/dsh-runtime.mjs` 支持环境变量 `DSHREMOTE_PLUGIN_PACKAGE`：设置后用 `dsh plugin add --offline <tgz>` 安装插件，不再链接源码目录。

**怎么测试**

- 单元测试 `test/unit/pack.test.js`（11 个）：
  - 文件名、版本号格式；
  - 包里的 `package.json` 保留 DSH 需要的字段；
  - 反向用例：包含 `test/`、`scripts/`、开发依赖、`*.pem`、`*.key`、`*.p12`、`.env` 等文件，或者缺少必需文件、缺少 `ws` 时，检查都不通过。
- 自动：`plugin.yml` 的 load job（Linux 和 Windows）打包后，用安装包离线装进 `dsh web`，再跑一遍 load-check。额外检查：插件是安装包的副本（不是链接）、版本一致、`ws` 在包里。
- 反向用例：用没有打包 `ws` 的 `.tgz` 安装时，load-check 失败。pnpm 本机缓存里有 `ws` 时 `--offline` 也会安装成功，只能靠这项检查发现。
- 同样的源码两次打包，SHA-256 相同。
- 用户在 Windows 上验证（T7）：用安装包安装和升级插件。

### T4 发布工作流

**做什么**

- 新的工作流 `.github/workflows/release.yml`：
  - 推送 `v*` 标签时：检查、构建、签名，然后创建 GitHub Release；
  - 手动触发（可以选任意分支）时：只是试运行，构建和检查都做，但不创建 Release，产物保留在这次运行的 artifacts 中。
- 各 job 的步骤：
  1. `version`：
     - 检查标签、`dshremote.version`、`plugin/package.json`、`plugin/package-lock.json` 四处版本一致；
     - 检查标签指向的提交在 `main` 上；
     - 检查 `CHANGELOG.md` 中有这个版本的说明。
  2. `android`、`plugin`：直接调用现有的 `android.yml` 和 `plugin.yml`（为此给它们加上 `workflow_call`）。检查和平时完全一样，包括 Windows 上的 load-check 和用安装包的 load-check。
  3. `build`：
     - 从 Secrets 还原密钥到 runner 的临时目录，运行 `assembleRelease`（`--no-daemon --no-configuration-cache`），无论成败都删除密钥文件；
     - Gradle 缓存只读，签名构建的产物不写进 GitHub 缓存；
     - 用 `apksigner` 和 `aapt2` 检查 APK：
       - 签名证书和 `android/release-cert.sha256` 一致，只有一个签名者，用了 v2 签名；
       - 包名、versionName、versionCode 正确，不可调试；
     - 打插件安装包，生成 `.sha256` 校验文件。更新说明取自 `CHANGELOG.md`，后面附上文件的 SHA-256 和 App 签名证书的指纹。
  4. `publish`：只在推送标签时运行，只有这个 job 有 `contents: write`。`gh release create --verify-tag`，rc 版标为预发布。
- 版本号检查、更新说明、APK 检查都在 `plugin/scripts/release-lib.mjs` 中（纯函数，有单元测试），命令行入口是 `plugin/scripts/release.mjs`。`bump <版本>` 一次改好三个文件中的版本号。
  - 版本号的规则和 `build.gradle.kts` 用同一个正则；
  - `pack-lib.mjs` 也改用这个规则。
- 新增 `CHANGELOG.md`，从 `0.1.0` 开始记录。每一节的标题是 `## X.Y.Z`；rc 版没有单独的一节时，用对应正式版的一节。
- 发版步骤：
  1. `node plugin/scripts/release.mjs bump X.Y.Z[-rc.N]`；
  2. 更新 `CHANGELOG.md`，提交，推送到 `main`；
  3. 推送标签 `vX.Y.Z[-rc.N]`。
  
  详细说明写进 `docs/release.md`（T6）。

**怎么测试**

- 单元测试 `test/unit/release.test.js`（16 个）：
  - 版本号的解析和 versionCode，拒绝的版本号和 Gradle 一致；
  - 版本号不一致（标签、三个文件）的反向用例；
  - 更新说明的提取（rc 版回退到正式版，找不到时失败）；
  - apksigner、aapt2 的输出解析，格式取自 build-tools 37.0.0 的真实输出；
  - APK 检查的反向用例：证书不一致、多个签名者、校验失败、debug 包、版本不对、可调试。
- 本地：
  - 对 debug 包运行 `check-apk`，四项问题都能报出来（证书、包名、versionName、可调试）；
  - `bump` 改过去再改回来，文件没有变化。
- 在 `main` 上手动触发试运行，确认：
  - 签名、检查都通过；
  - 产物齐全，校验和正确；
  - 日志中没有密钥和密码。
- 反向用例：在测试分支上把 `release-cert.sha256` 改成错误的值，手动触发，确认在「检查 APK」一步失败。测试分支用完删除。
- 推送预发布标签 `v0.1.0-rc.1`：
  - Release 标为预发布，产物和校验和正确；
  - 下载的 APK 能装到手机上（见 T7）。

**结果（2026-11-11 完成）**

- 插件单元测试 267 个通过，其中 `release.test.js` 16 个；`VersionTest` 在 `0.1.0-rc.1` 下通过。
- 在 `main` 上手动试运行（run 42968695217）通过：签名证书指纹和 `release-cert.sha256` 一致（`438e5425…9532`），日志中的密钥和密码只显示为 `***`。插件安装包在本机和 CI 上打出的 SHA-256 相同。
- 反向用例（run 42968752251）：在测试分支上把 `release-cert.sha256` 改成全 0，在「检查 APK」一步失败。测试分支已删除。
- 试运行时发现：工作流调用的 `android.yml`、`plugin.yml` 和推送 `main` 触发的同名检查在同一个 concurrency 分组，会互相取消。现在分组名中加上调用方的工作流名（66631ef）。
- 推送 `v0.1.0-rc.1`（run 42972839567）全部通过，Release 标为预发布，有 APK、插件安装包和两个 `.sha256` 文件。重新下载后校验和一致，`check-apk` 通过。
- APK 有 34 MB：主要是随包分发的中文字体（Noto Sans SC，约 30 MB），并且没有开 R8（第 1 节）。v1 自用可以接受；要缩小时先考虑字体子集化，再评估 R8。
- 手机上安装 rc.1 并走查，在 T7 中进行。

### T5 修复会话标题中的「#」

**做什么**

DSH 用第一条消息生成会话标题，消息以 Markdown 标题开头时，标题会带上「#」。在 App 显示会话标题的地方去掉开头的 Markdown 标记（`#`、`>`、`-`、`*` 等）。只改显示，不改协议，插件原样转发。

**怎么测试**

- JVM 单元测试：各种前缀、只有符号、正文中间的「#」不受影响。

**结果（2026-11-12 完成）**

- DSH 生成标题时会把第一条消息压成一行（`dsh-session-title` 的 `fallbackSessionTitle`），所以只需要处理开头。
- `ui/common/SessionTitle.kt` 的 `sessionDisplayTitle()` 去掉开头的这些标记，可以叠加：
  - 标题 `#`–`######`；
  - 引用 `>`；
  - 无序列表 `-` `*` `+`；
  - 任务框 `[ ]` `[x]`。
- 按 CommonMark 的规则，`#123`、`*强调*`、`---`、`1.` 不算标记，原样保留。只有标记的标题显示为「未命名会话」。
- 用在四处：首页会话列表、旧的会话列表、会话详情页的标题栏、「任务完成」通知。
- 测试：
  - JVM：`SessionTitleTest`（5 个）；全部 253 个通过，`lint`、`ktlintCheck`、`assembleDebug` 通过。
  - 真机：`HomeLayoutUiTest` 新增一项，检查首页显示的标题不带「##」；小米 Android 17 上 `connectedDebugAndroidTest` 61 项通过。

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

- [x] 每天的兼容性检测在运行；手动指定一个不兼容的版本时开出 issue，同一个版本不重复开（T1：issue #1「DSH 0.2.1-rc.5 兼容性检测失败」，验证后已关闭）
- [x] 推送标签后，Release 上有签名的 APK、插件安装包和校验和；签名证书指纹检查的反向用例失败（T4）
- [ ] release 包和 debug 包可以同时安装在手机上
- [ ] 插件安装包在 Linux 的 `dsh web` 和 Windows 的官方 Desktop 上都能安装；覆盖升级后配对保留（Linux 和 Windows 上的 `dsh web` 已由 CI 覆盖；Windows Desktop 和覆盖升级待 T7）
- [ ] `README.md`、`docs/user-guide.md`、`docs/release.md` 完成，用户按用户手册从头安装成功
- [x] 会话标题不再出现「#」（T5）
- [ ] 在 Windows 官方 Desktop 上，用 release 包走通 `spec-v1.md` 的验收场景 1–7
- [ ] 记录了不关闭电池优化时的通知表现
- [ ] 发布 `v0.1.0`
