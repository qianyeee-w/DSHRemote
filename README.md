# DSHRemote

用 Android 手机通过局域网，安全地操控电脑上的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（DSH）。

- **能做什么**：在手机上查看会话进度、发消息、处理审批和提问；Agent 需要你时，手机收到通知。
- **不依赖第三方**：不需要账号，也没有中转服务器，数据只在手机和电脑之间传输。手机和电脑在同一个 Wi-Fi 下就能用；要在外面使用，可以自行配置 Tailscale、VPN 等。
- **安全**：全程 TLS 加密；扫码配对，手机只认你电脑的证书指纹；每部手机都可以单独撤销。

> 项目处于早期开发阶段，还不能使用。

## 组成

| 目录 | 内容 |
|---|---|
| `plugin/` | 电脑端插件，装在官方 DSH Desktop 或 `dsh web` 上 |
| `android/` | Android App（Android 8.0 及以上） |
| `docs/` | 功能范围、接口约定、安全模型、架构决策 |

## 文档

- [第一版功能范围](docs/spec-v1.md)
- [App ↔ 插件 接口约定](docs/protocol.md)
- [安全模型](docs/security.md)
- [架构决策](docs/decisions/README.md)
- [后续优化方向](docs/roadmap.md)
- [开发指南（给人和 agent）](AGENTS.md)

## 适配版本

DeepSeek Harness `0.2.2-rc.1`。

本项目是社区作品，与 DeepSeek 官方无关。
