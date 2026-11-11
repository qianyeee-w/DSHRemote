# DSH 安装第三方插件的方式（安装包格式的依据）

- 日期：2026-11-11
- 对象：`@deepseek-ai/dsh@0.2.2-rc.1`，pnpm 10.34.9，Linux
- 用途：M3 T3 决定插件安装包的格式（`docs/plans/m3-release.md`）
- 结论：安装包用 npm 格式的 **`.tgz`**，把运行时依赖 `ws` 打包在里面，文件名 `dshremote-plugin-X.Y.Z.tgz`；用户在 Desktop「添加插件」中填它的绝对路径。见第 4 节

## 1. 资料来源

| 来源 | 内容 |
|---|---|
| `@deepseek-ai/dsh` 的 `lib/bin.js`、`lib/plugin-*.js` | `dsh plugin --profile <名称> <参数>` 把参数原样交给 profile 目录中的 `pnpm` |
| `@deepseek-ai/dsh-plugin-manager`：`README.zh.md`、`lib/types/install-spec.js`、`lib/types/operations.js`、`lib/typert.host.js` | Desktop 和 Web 的「添加插件」调用 `pluginManager/installBundle`，内部运行 `pnpm add <spec>` |
| `@deepseek-ai/dsh-client-ui-plugin-manager` 的界面文案 | 「添加插件」接受「插件的包名、GitHub 仓库地址或本地目录路径」，安装结果中有「本地目录」「Git 仓库」「压缩包」三类 |

Desktop 和 `dsh web` 用的是同一套插件管理代码（Desktop 的界面也是这个 Web 界面），所以下面在 `dsh web` 上的实测结论同样适用于 Desktop。Windows 上的实际操作在 T7 由用户验证。

## 2. 「添加插件」接受的写法

`install-spec.js` 的 `parseInstallSpec`：

| 写法 | 类型 | 说明 |
|---|---|---|
| `dsh-plugin-xxx`、`@作者/xxx@1.0.0` | registry | 从 npm 源安装 |
| `github:…`、`git+https://…` 等 | git | |
| 绝对路径，以 `.tgz` 或 `.tar.gz` 结尾 | tarball | 本地压缩包 |
| 其他绝对路径 | path | 本地目录 |
| 相对路径 | — | 拒绝：「a local path must be absolute」 |

安装前会读出包的 `engines.dsh`，和当前 DSH 版本不兼容时直接拒绝（本地目录读 `package.json`；`.tgz` 要装完才能读，不兼容时自动回滚）。

## 3. 实测

脚本放在 scratchpad 中，没有提交。方法：

- 用 `dsh plugin --profile web add` 安装到全新的 `DSH_HOME`；
- 启动 `dsh web`，通过它的 `pluginManager/inspect`、`installBundle`、`removeBundle` 接口（Desktop 界面调用的就是这些接口）安装、升级、卸载；
- 每一步之后读取插件列表、profile 中安装的版本、插件数据目录和 `pnpm-lock.yaml`。

| # | 操作 | 结果 |
|---|---|---|
| 1 | 安装**本地目录**（`npm pack` 解开，不带 `node_modules`） | profile 记为 `link:<目录>`，`node_modules/dshremote` 是指向原目录的**符号链接**；**不安装依赖**（没有 `ws`），插件会因为缺少 `ws` 无法加载 |
| 2 | 安装**本地 `.tgz`**（不打包依赖） | profile 记为 `file:<路径>.tgz`，插件**复制**到 profile 的 `node_modules`；`ws` 从 npm 源**联网下载** |
| 3 | 安装**打包了 `ws` 的 `.tgz`**（`bundleDependencies`），加 `--offline` | 成功，`ws` 来自安装包，不需要联网 |
| 4 | 安装后把 `.tgz` 改名（模拟用户删除下载的文件），再装另一个插件或运行 `pnpm install` | **失败**：`ENOENT … dshremote-0.1.0.tgz`。改回原名后正常。`pnpm-lock.yaml` 中记着这个文件的路径，profile 里以后任何 pnpm 操作都会去读它 |
| 5 | 已装 0.1.0，通过 `installBundle` 直接安装 0.1.1 的 `.tgz` | 成功，原地升级，插件处于 active；锁文件中只剩 0.1.1 的 `.tgz`，0.1.0 的文件可以删除 |
| 6 | `removeBundle` 卸载，再 `installBundle` 安装 | 都成功；卸载后锁文件中不再有 `.tgz` 的引用 |
| 7 | 5、6 前后比较插件数据 | `<DSH_HOME>/dshremote/` 中的证书和私钥不变（证书哈希相同），所以**指纹不变、配对保留**；profile 的 `cordis.patch.yml` 中用户写的插件配置（端口等）在卸载、重装后也保留 |

另外：

- Desktop 界面上写着「其他插件请先卸载再安装新版」。实测 5 说明直接安装新版也可以，但用户手册按界面的说法写「先卸载再安装」，两种都能保留配对。
- `.tgz` 由 `npm pack` 生成，文件时间固定为 1985-10-26，同样的源码两次打包的 SHA-256 相同。

## 4. 结论

| 问题 | 结论 |
|---|---|
| 引用原目录还是复制 | **本地目录：引用（符号链接）；`.tgz`：复制**。但两种方式下原文件都**不能删除或移动**，否则这个 profile 以后的 pnpm 操作（包括安装和更新其他插件）都会失败 |
| 会不会自己安装依赖 | 本地目录：不会。`.tgz`：会，从 npm 源下载；如果依赖已打包在 `.tgz` 中，就直接使用，不需要联网 |
| 升级 | 卸载旧版、安装新版（或直接安装新版）。插件数据不在插件目录里，配对保留 |

所以安装包选 `.tgz`，不用计划中原来写的 zip：

- zip 要先解压，得到的是本地目录：
  - 必须把 `node_modules/ws` 一起放进 zip；
  - DSH 只是链接这个目录，用户以后不小心改动、删除或覆盖它，正在用的插件就会跟着变；
  - Windows 上的符号链接（junction）也多一层不确定性。
- `.tgz` 不用解压，直接填路径安装：
  - pnpm 会校验完整性，并把插件复制进 profile；
  - `ws` 打包在里面，不依赖用户能不能访问 npm 源。

用户需要注意的只有一点：**`.tgz` 要放在固定的位置，不能删除或移动**，升级到新版本后旧文件才可以删除。这一点写进用户手册和插件的 README。

## 5. 实现

- `plugin/scripts/pack.mjs`（`npm run pack`）：
  - 把 `package.json` 中 `files` 列出的文件、`README.md` 和运行时依赖放进临时目录；
  - 写入去掉开发依赖和脚本、声明 `bundleDependencies` 的 `package.json`；
  - 运行 `npm pack`，检查包里的文件（`scripts/pack-lib.mjs` 的 `checkPackFiles`，有单元测试）。
- CI（`plugin.yml` 的 load job，Linux 和 Windows）：打包后设置 `DSHREMOTE_PLUGIN_PACKAGE`，用 `dsh plugin add --offline <tgz>` 装进 `dsh web` 再跑一遍 load-check。load-check 额外检查：profile 中的插件是副本而不是链接，版本和源码一致，`ws` 在包里。
- 反向用例：用没有打包 `ws` 的 `.tgz` 安装，load-check 失败（「安装包中没有打包运行时依赖 ws」）。注意 `--offline` 本身挡不住这种情况：pnpm 的本机缓存里已经有 `ws` 时，离线安装也会成功，`ws` 被装在 profile 的 `node_modules` 下，插件照常运行。所以 load-check 要直接检查 `ws` 是否在插件包自己的 `node_modules` 中。
