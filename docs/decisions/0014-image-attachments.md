# 0014 图片附件：读模型能否看图，按引用读会话里的图片

- 状态：已接受
- 日期：2026-11-14
- 范围：落地方案 C2（`docs/plans/ui-redesign.md`），用户确认的三点见下文「决策」。

## 背景

DSH 0.2.2-rc.1 的公开接口已经能收发图片（调研文档 9.7）：

- `session/prompt` 的内容里可以直接放 `{ type: 'image', mediaType, data }`（base64），DSH 严格校验，坏图返回 `session/attachment-invalid`（`INVALID_IMAGE`）。
- 只能读文字的模型直接拒绝带图的消息（`session/attachment-invalid`，`MODEL_DOES_NOT_SUPPORT_IMAGES`）。**只能上传不等于能看图**。
- 会话记录里只存图片的引用（`attachmentId` 为 `sha256:…`，带格式、尺寸、字节数）；`session/attachment` 按完整引用读回 base64，**但不检查图片是否属于这个会话**。

问题有两个：

1. 设计要求「当前模型看不了图时，加号不可用并说明原因」。`session/modelCatalog` 没有能否看图的信息；这个信息只在 LLM 服务（`dsh-llm`）的 `LlmModelInfo.inputModalities` 里，经 `ctx.llm.listModels(provider)` 读取。插件现在只依赖 `webServer` 和 `connection`。
2. 手机要显示历史消息里的图片。如果插件原样转发 `session/attachment`，任何设备都能凭一个引用读电脑上任意会话的图片，相当于开了一个通用读取通道（违反 ADR 0007）。

## 决策

**事先知道模型能否看图（用户确认 1）**

- 插件**可选地**依赖 LLM 服务：在 `apply` 里用 `ctx.inject(['llm'], …)` 等它出现，不写进插件导出的 `inject`。拿不到这个服务时插件照常加载，所有功能不受影响。
- 只调用 `dsh-llm` README 公开的只读方法 `ctx.llm.listModels(provider)`，取每个模型的 `inputModalities`，在 `/v1/models` 顶层加 `imageInput: true`（表示插件能发图片，旧插件没有），每一项里加 `images`：含 `image` 为 `true`，有 `inputModalities` 但不含 `image` 为 `false`，没有这项（DSH 的约定是「不知道」）或拿不到 LLM 服务时不带这个字段。
- App：当前模型 `images: false` 时加号不可用并说明原因；不知道时照常可用，DSH 拒绝时再说明（退路）。

**按引用读图片，只读这个会话里的（用户确认 2）**

- 消息里的图片改为 `{ type: 'image', id, mediaType, width, height }`，`id` 是 `sha256:` 后面那串十六进制（64 个字符）。
- 新增 `GET /v1/sessions/{id}/images/{imageId}`：插件只在**这个会话的记录里找到过这个引用**时，才用记录里的完整引用调用 `session/attachment`；找不到一律 `404`，不区分「图片不存在」和「不属于这个会话」。返回图片本身（`Content-Type` 为图片格式），不返回 base64 JSON。
- 只处理用户消息里的图片。工具结果里的图片（如 `read_image`）仍然显示占位文字。

**手机端先压缩（用户确认 3）**

- App 把图片缩到长边不超过 2048 像素：不透明的编码为 JPEG，带透明的编码为 PNG；每条消息最多 4 张。插件按这个上限校验，单张解码后最多 8 MB，发消息接口的请求体上限相应放宽。

## 理由

- 能否看图是模型的属性，只有 LLM 服务知道；用公开的只读方法读取，符合 ADR 0005。可选依赖保证 DSH 以后不开放这个服务时，插件仍能加载，只是退回到「发了再说」。
- 插件自己确认图片属于这个会话，堵住 `session/attachment` 不检查归属的缺口；App 只拿到这一项明确的操作，不是通用读取通道（ADR 0007）。
- 手机上的原图动辄十几 MB，DSH 发给模型前本来也要缩小。先在手机上压缩，局域网传输量和插件内存都可控。

## 代价／后果

- 插件多依赖一个 DSH 服务的接口形状（`listModels` 和 `inputModalities`）。升级 DSH 基线时要一起确认，契约测试覆盖。
- 插件要在会话记录里找图片引用，记录很长时第一次读一张旧图片会慢一些；插件缓存已经见过的引用。
- 发消息接口的请求体上限从 512 KB 放宽到约 45 MB，只对这一个接口。
