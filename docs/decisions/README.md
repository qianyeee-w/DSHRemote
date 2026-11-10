# 架构决策记录（ADR）

每条重要决策一个文件，编号递增，写清楚「当时为什么这么定」。

- 已接受的决策不要直接修改。想推翻某条决策时，新写一条 ADR，在新文件里说明它替代了哪一条，再把旧文件的状态改为「已被 XXXX 取代」。
- 格式：背景 → 决策 → 理由 → 代价 / 后果。

| 编号 | 标题 | 状态 |
|---|---|---|
| [0001](0001-lan-direct-only.md) | v1 只做直连，网络由用户解决 | 已接受 |
| [0002](0002-tls-pinning-over-noise.md) | 使用 TLS + 证书指纹固定，不用 Noise | 已接受 |
| [0003](0003-always-encrypt.md) | 加密始终开启，不提供关闭选项 | 已接受 |
| [0004](0004-android-only.md) | v1 只做 Android（Kotlin + Compose） | 已接受 |
| [0005](0005-dsh-public-api-only.md) | DSH 基线 0.2.2-rc.1，只用公开接口 | 已接受 |
| [0006](0006-plugin-windows-linux-only.md) | v1 插件只适配 Windows 和 Linux | 已接受 |
| [0007](0007-plugin-facade-api.md) | 插件对 App 提供外观层接口，不透传 DSH 协议 | 已接受 |
| [0008](0008-owner-principal-for-v1.md) | v1 插件以 owner 身份访问 DSH | 已接受 |
| [0009](0009-android-fgs-special-use.md) | 前台服务使用 `specialUse` 类型，不用 `dataSync` | 已接受 |
| [0010](0010-android-build-baseline.md) | Android 构建基线：targetSdk 35，applicationId `io.github.qianyeeew.dshremote` | 已被 0011 取代 |
| [0011](0011-android-compile-sdk-37.md) | Android 构建基线：compileSdk 37，targetSdk 仍为 35 | 已接受 |
| [0012](0012-v1-release.md) | v1 的发布方式：GitHub Releases + CI 签名，App 和插件共用版本号，不开 R8 | 已接受 |
