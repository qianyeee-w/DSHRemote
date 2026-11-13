# src/vendor

这里放的是直接复制进来的第三方代码，不经过 npm 安装，所以安装插件时不需要额外的依赖。

| 文件 | 来源 | 版本 | 许可证 |
|---|---|---|---|
| `qrcode.mjs` | [qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator) 的 `dist/qrcode.mjs` | 2.0.4 | MIT，Copyright (c) 2009 Kazuhiko Arase（文件头保留了原始声明） |
| `dsh-turn-usage.mjs` | `@deepseek-ai/dsh-token-meter` 的 `lib/types/turn-usage.js`（每轮用量 `deriveTurnTokenUsage`，DSH 官方界面用它算「本轮用量」），以及 `@deepseek-ai/dsh-llm` 的 `lastAssistantStreamChunk` | 0.2.2-rc.1 | MIT，Copyright (c) 2026 DeepSeek |

更新规则：

- 只能用上游发布的文件**原样替换**，不做任何修改。文件第一行写明来源和版本。
- 替换后，在本表中更新版本号，并在提交信息里写明上游文件的 SHA-256。
- **例外**：`dsh-turn-usage.mjs` 不能原样使用，因为它 import 了另一个 DSH 包，插件里解析不到。唯一的改动是把那一行 import 换成被引用函数 `lastAssistantStreamChunk` 的原文（去掉 `export`），并去掉末尾的 sourceMappingURL 注释；开头三行注释写明了来源和改动。随 DSH 基线升级时，按同样的方式从新版本重新生成，并跑 `npm run contract`（契约测试里有真实 DSH 上的本轮用量检查）。
- `dsh-turn-usage.mjs` 对应上游 `turn-usage.js` 的 SHA-256：`6cee2c03747d47abdc191568a445efe128a85a1b06f86c8be7a9d55b94d3d9b8`
- `qrcode.mjs`（不含第一行）对应上游 `dist/qrcode.mjs` 的 SHA-256：`ea91d7118a5395289170da848b7c6758b996163bfbccf312591ab65a4911b7c0`

"QR Code" 是 DENSO WAVE INCORPORATED 的注册商标。
