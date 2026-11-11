# Android 内容优先界面改版

用户已于 2026-11-10 确认四页预览并授权完整开发与真机测试。本任务按已确认的「正文舒适、控件紧凑」落地，不升级依赖或协议。

## 范围

- `ui/Theme.kt`、新字体与通用组件：固定中性明暗配色，跟随系统；正文 15sp、输入与用户消息 14sp、代码 12.5sp。字体随 APK 离线分发，附许可证。中文 Noto Sans SC、英文 Inter、代码 JetBrains Mono；Android 8–9 使用 Noto Sans SC 的完整字形作为兼容回退。
- `ui/chat/`：淡灰点阵、开放式 AI 正文、紧凑工具行；输入框默认单行，焦点／草稿／选择器打开时显示框内模型和权限。保留发送、插话、停止、失败重试、历史分页与阅读位置。按钮有效点击区域不小于 48dp。
- `ui/attention/`：审批与提问使用中性描边容器，待处理为琥珀色，真正错误仍为错误色。保留单次审批说明、参数、提交状态和跨端处理结果。
- `ui/hosts/`、`ui/settings/`、通用返回栏：居中标题、圆形导航按钮和分组列表。连接状态、指纹错误、重新配对与系统通知引导仍完整可达。
- `ui/sessions/`、`ui/AppNavigation.kt`：主机切换、按工作区展开会话、待处理事项优先、未归属项目的最近会话与底部新会话入口。保留已批准的创建／导入项目流程。
- 动效用于展开与状态变化；复制和选择等明确操作使用系统触感，流式正文不逐字动画或振动。

## 协作与顺序

创建／导入项目的另一任务正在修改首页、导航和 `strings.xml`。用户确认先避开这些文件；本任务先实现主题、聊天、主机和设置页。新增字符串单独放在 `values/design_strings.xml`，待另一任务结束后整合首页，保留其全部改动。

## 验证

1. 为输入框展开／收起、草稿保持、选择器回调、离线禁用、项目分组与待处理优先补测试；沿用审批安全和配对拒绝测试。
2. `test`、`lint ktlintCheck`、`assembleDebug`。
3. 在已连接且解锁的真机运行 `connectedDebugAndroidTest -Pdshremote.debugApplicationIdSuffix=.projecttest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`，保留不同签名的 `.debug` 应用并检查实际测试数量。
4. 安装调试包检查首页／项目选择、主机、设置、长 Markdown 与代码、输入框两态、键盘与审批面板共存、字体缩放及明暗模式。仅操作本 App；手机已有其他签名的 `.debug` 包时用 `.projecttest` 后缀。
5. 若为首页调整实时连接／接口访问，按子项目约定执行 `devServerTest`。截图与验证结论只在实际运行后记录。

## 结果

实现完成，自动化验证通过；页面手动视觉走查和截图尚待补充。

- 主题、离线字体、聊天输入框、Markdown／工具区、主机与设置页、首页分组和项目选择均已整合。中文 Noto Sans SC、英文 Inter、代码 JetBrains Mono 与字体许可证随 APK 分发。
- `test lint ktlintCheck assembleDebug` 全部通过，JVM 实际运行 248 项测试，0 失败；Android `devServerTest` 的 20 项联调测试通过。
- 小米 Android 17 的 `connectedDebugAndroidTest` 通过，60 项测试、0 失败，覆盖输入框、首页折叠与主机切换、主机管理、设置、长 Markdown、审批、Keystore 和 TLS。测试使用独立 `.projecttest` 包名，没有替换另一台电脑签名的 `.debug` 应用。
- 首轮真机测试发现每次 Compose 内容创建都会重新加载多份约 10 MB 的中文字库，造成 instrumentation 进程 OOM。字体族现改为进程级缓存；之后 60 项真机测试全部通过。
- 自动化测试已覆盖页面结构和关键交互；仍需人工检查字体缩放、明暗模式、键盘与审批面板共存等实际显示，并保存走查截图。
- 本机首次构建缺少多项依赖。采用官方 Maven/Google 源补齐固定版本并校验哈希，未调整项目依赖；依赖已进入本地缓存。
