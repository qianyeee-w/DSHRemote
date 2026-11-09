# 插件生成的证书（测试固定数据）

用于跨实现的指纹一致性测试（`docs/plans/m2-android.md` 3.1）：App 算出的指纹必须和插件算出的完全一致。

| 文件 | 内容 |
|---|---|
| `key-a-cert-1.crt` | 私钥 A 签发的证书 |
| `key-a-cert-2.crt` | 同一把私钥 A 重新签发的证书（有效期、名称、序列号都不同），指纹应和上一张相同 |
| `key-b-cert.crt` | 另一把私钥 B 签发的证书，指纹应不同 |
| `fingerprints.json` | 插件的 `spkiFingerprint` 对每张证书算出的指纹 |

- 用插件的 `plugin/src/tls/certificate.js`（`generatePrivateKeyPem`、`issueSelfSignedCertificate`、`spkiFingerprint`）生成。**私钥用完即丢弃，没有保存，也不得提交**（安全红线第 5 条）。
- 两边都有测试：App 的 `FingerprintTest`（JVM 和真机各跑一遍）检查 App 的结果，插件的 `test/unit/android-cert-fixtures.test.js` 检查插件的结果，并确认目录中没有私钥。
- 需要重新生成时，按上表用插件的函数生成三张证书，覆盖这里的文件，再跑两边的测试。
