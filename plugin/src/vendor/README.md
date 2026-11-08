# src/vendor

这里放的是直接复制进来的第三方代码，不经过 npm 安装，所以安装插件时不需要额外的依赖。

| 文件 | 来源 | 版本 | 许可证 |
|---|---|---|---|
| `qrcode.mjs` | [qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator) 的 `dist/qrcode.mjs` | 2.0.4 | MIT，Copyright (c) 2009 Kazuhiko Arase（文件头保留了原始声明） |

更新规则：

- 只能用上游发布的文件**原样替换**，不做任何修改。文件第一行写明来源和版本。
- 替换后，在本表中更新版本号，并在提交信息里写明上游文件的 SHA-256。
- `qrcode.mjs`（不含第一行）对应上游 `dist/qrcode.mjs` 的 SHA-256：`ea91d7118a5395289170da848b7c6758b996163bfbccf312591ab65a4911b7c0`

"QR Code" 是 DENSO WAVE INCORPORATED 的注册商标。
