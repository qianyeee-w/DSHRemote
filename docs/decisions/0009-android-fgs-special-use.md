# 0009 前台服务使用 `specialUse` 类型，不用 `dataSync`

- 状态：已接受
- 日期：2026-11-09

## 背景

v1 没有云推送（ADR 0001），App 在后台时靠前台服务保持与电脑的 WebSocket 连接，才能在需要审批或提问时立即通知用户。`android/AGENTS.md` 最初选定的前台服务类型是 `dataSync`。

App 的 targetSdk 为 35 及以上、系统为 Android 15 及以上时，`dataSync` 前台服务在任意 24 小时内**最多运行 6 小时**：到点后系统回调 `onTimeout()`，服务必须在几秒内停止，否则 App 崩溃；额度用完后，只有用户把 App 打开到前台才会重置计时。官方还建议这类服务迁移到其他 API。

用户的测试真机是 Android 17，这个限制会生效。

## 决策

前台服务使用 **`specialUse`** 类型，在 manifest 中声明 `FOREGROUND_SERVICE_SPECIAL_USE` 权限和 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 用途说明（「保持与用户自己电脑上 DSH 的连接，以便在需要审批或回答提问时通知用户」）。

前台服务的类型只在一处定义，以后需要更换时只改一处。

## 理由

- `dataSync` 出问题的场景正是 v1 最核心的场景：锁屏，让 Agent 长时间运行，等它需要审批时通知你。6 小时到点后通知会**悄悄停止**；而插件安装后审批会一直等待（`security.md`），结果是 Agent 卡住，用户却以为它还在运行。
- `specialUse` 没有时长限制。项目直接分发 APK，不经过 Google Play 审核，声明 `specialUse` 没有障碍。
- 其他类型都不贴切：`connectedDevice` 用于外部硬件，`remoteMessaging` 用于在设备之间转发短信。

## 代价 / 后果

- 以后如果上架 Google Play，需要向审核说明 `specialUse` 的用途。
- 无论哪种类型，国产系统的省电策略都可能杀掉前台服务，仍然需要引导用户加入电池优化白名单、允许自启动（spec 已有要求）。
- 前台服务不能从后台启动（Android 12 起的限制），只在用户打开 App 时启动；v1 不做开机自启。
