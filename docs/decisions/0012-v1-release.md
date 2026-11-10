# 0012 v1 的发布方式：GitHub Releases + CI 签名

- 状态：已接受
- 日期：2026-11-11
- 补充：[0010](0010-android-build-baseline.md)、[0011](0011-android-compile-sdk-37.md) 中「版本号」「签名」两项

## 背景

M2 完成后只有 debug 包。debug 包用开发机上 Android SDK 自动生成的调试密钥签名：换一台电脑构建，或者开发机重装，新包就无法覆盖安装，手机上的配对全部丢失。v1 要能长期使用，需要正式的发布密钥和固定的发布流程（`docs/plans/m3-release.md`）。

v1 目前只有用户自己使用，仓库保持私有；以后可能开源。

## 决策

| 项目 | 决定 |
|---|---|
| 发布渠道 | 本仓库的 GitHub Releases：签名的 APK、插件安装包和校验和。不上架应用商店，App 内不检查更新 |
| 版本号 | App 和插件共用一个版本号，只在 `android/gradle.properties` 的 `dshremote.version` 和 `plugin/package.json` 中出现，发布时 CI 检查两者和标签 `vX.Y.Z` 一致。格式 `X.Y.Z` 或 `X.Y.Z-rc.N` |
| versionCode | `X * 1000000 + Y * 10000 + Z * 100 + N`，正式版 N 取 99。rc 早于正式版，正式版早于下一个版本 |
| 签名密钥 | PKCS12，RSA 4096，有效期 30 年。由用户在自己的终端里运行 `android/scripts/create-release-key.sh` 生成，保存在 GitHub Secrets 中，另由用户离线备份。证书的 SHA-256 指纹提交在 `android/release-cert.sha256`，发布时检查 APK 的签名证书和它一致 |
| 签名方式 | 推送标签后由 CI 构建并签名。签名信息只从环境变量读取；缺少时打包 release 直接失败，不生成未签名或用调试密钥签名的包 |
| R8 | 不开启 |
| debug 包 | applicationId 加后缀 `.debug`，versionName 加后缀 `-debug`，应用名「DSHRemote 调试」 |

## 理由

- **CI 签名**：用户的 Windows 电脑上没有 Android 构建环境；CI 签名不需要。GitHub Secrets 写入后无法读回，只能被工作流使用，日志中会自动打码。
- **检查签名证书指纹**：如果 Secrets 被换成了别的密钥，发布流程会失败，不会发出一个无法覆盖升级、来源也不明的包。
- **共用版本号**：只有一个使用者，App 和插件分开发布没有好处；两端的兼容性仍然由 `protocol.md` 的协议版本协商保证，不依赖版本号。
- **不开启 R8**：开启后，依赖反射的代码（序列化等）可能被破坏，需要 keep 规则和完整的真机回归测试。v1 自己使用，包大一些没有关系。
- **debug 包加后缀**：release 包和 debug 包可以同时装在手机上，日常用 release 包，开发测试用 debug 包，互不影响。配对页的设备列表里也能从版本号看出是哪个包。

## 代价 / 后果

- **发布密钥一旦丢失，就无法覆盖升级**：只能卸载重装，所有配对都要重做。所以必须离线备份，生成脚本在仓库里已有密钥时拒绝运行。
- 从旧的 debug 包（没有后缀，调试密钥签名）换到 release 包时，要先卸载旧包；release 包和新的 debug 包都需要重新配对一次。
- 以后要开启 R8、上架应用商店或公开发布时，再写新的 ADR。
