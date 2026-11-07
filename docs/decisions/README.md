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
