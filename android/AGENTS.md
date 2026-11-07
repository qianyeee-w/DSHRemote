# android/ — Android App

Kotlin + Jetpack Compose，minSdk 26。先读根目录的 `AGENTS.md`。

> 状态：骨架还没建立，M2 开始时补齐。下面的命令和目录是计划中的约定，建立骨架时按此执行；如有调整，同步更新本文件。

## 命令

```sh
cd android
./gradlew test             # 单元测试
./gradlew lint ktlintCheck
./gradlew assembleDebug
```

## 技术选型（建骨架时按此执行，要改需要先写 ADR）

| 用途 | 选型 |
|---|---|
| UI | Jetpack Compose + Material 3 |
| 架构 | 单 Activity，MVVM（ViewModel + StateFlow） |
| 网络 | OkHttp（HTTP + WebSocket） |
| JSON | kotlinx.serialization |
| 依赖注入 | 暂不引入框架，手动注入；规模变大后再考虑 Hilt |
| 本地存储 | DataStore；敏感数据（token、指纹）用 Android Keystore 加密 |
| 扫码 | CameraX + ZXing |
| 后台 | 前台服务（`dataSync` 类型）+ 本地通知 |

## 目录（计划）

```
android/app/src/main/java/.../
├── data/
│   ├── net/        # OkHttp 客户端、证书指纹校验、WebSocket
│   ├── host/       # 主机列表、配对、凭据存储
│   └── dsh/        # DSH 会话相关接口
├── service/        # 前台服务、通知
└── ui/             # 各页面：连接、会话列表、会话详情、设置
```

## 约定

- **证书校验只能写在一个地方**（`data/net/` 下），所有网络请求都必须经过它。不允许出现第二个 OkHttpClient 绕过校验。
- 指纹校验必须有单元测试，至少覆盖：指纹正确时通过、指纹错误时拒绝、没有证书时拒绝。
- token 和指纹只能存在 Keystore 加密的存储里，不允许写进 SharedPreferences 明文或日志。
- 字符串放进 `strings.xml`，不要硬编码，为第二版的中英文界面做准备。
- 开发界面时，可以连接插件的 mock server，不需要每次都启动真的 DSH。
