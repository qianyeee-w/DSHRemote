// @ts-check
/**
 * 图片附件（protocol.md 第 4 节「图片」，ADR 0014）。
 *
 * - 发送：校验 App 发来的图片（格式、张数、大小、文件头），转换成 DSH session/prompt 的 image 内容块。
 * - 读取：DSH 的 session/attachment 不检查图片属于哪个会话，所以插件只用这个会话记录里出现过的完整引用去读。
 * - 模型能否看图：从 DSH 模型服务的 inputModalities 得出。
 */

/** 每条消息最多几张 */
export const MAX_IMAGES = 4
/** 单张解码后最多多少字节 */
export const MAX_IMAGE_BYTES = 8 * 1024 * 1024
/** App 能发的格式（手机压缩后只有这两种） */
const SEND_TYPES = new Set(['image/jpeg', 'image/png'])
/** 读回时允许的格式（电脑上发的图片还可能是 webp、gif，和 DSH 的 imageLimits 一致） */
const READ_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif'])
/** 标准 base64 的字符，填充只在末尾。用简单的字符类：带分组重复的正则在几 MB 的输入上会耗尽调用栈 */
const BASE64_CHARS = /^[A-Za-z0-9+/]*={0,2}$/
/** 协议里的图片 id：DSH attachmentId 去掉 sha256: 前缀 */
export const IMAGE_ID = /^[0-9a-f]{64}$/
const ATTACHMENT_ID = /^sha256:([0-9a-f]{64})$/

/** @param {unknown} value @returns {value is Record<string, any>} */
const isObject = (value) => typeof value === 'object' && value !== null && !Array.isArray(value)
/** @param {unknown} value */
const isPositiveInt = (value) => typeof value === 'number' && Number.isSafeInteger(value) && value > 0

/** 校验失败：路由转成 400 BAD_REQUEST，message 不带图片内容 */
export class ImageInputError extends Error {}

/** @param {Buffer} bytes @param {string} mediaType */
function hasSignature(bytes, mediaType) {
  if (mediaType === 'image/png') return bytes.length >= 8 && bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))
  if (mediaType === 'image/jpeg') return bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff
  return false
}

/**
 * 校验 App 发来的 images，转换成 DSH 的内容块。没有 images 时返回空数组。
 * 只检查格式、张数、大小和文件头；图片能不能解码由 DSH 校验（坏图返回 INVALID_IMAGE）。
 * @param {unknown} images
 * @returns {{ type: 'image', mediaType: string, data: string }[]}
 */
export function parseOutgoingImages(images) {
  if (images === undefined) return []
  if (!Array.isArray(images)) throw new ImageInputError('images 必须是数组')
  if (images.length > MAX_IMAGES) throw new ImageInputError(`每条消息最多 ${MAX_IMAGES} 张图片`)
  return images.map((image, index) => {
    const n = index + 1
    if (!isObject(image)) throw new ImageInputError(`第 ${n} 张图片格式不对`)
    if (typeof image.mediaType !== 'string' || !SEND_TYPES.has(image.mediaType)) throw new ImageInputError(`第 ${n} 张图片只能是 JPEG 或 PNG`)
    const data = image.data
    // 先按长度粗筛，避免对超大字符串跑正则
    if (typeof data !== 'string' || data.length === 0 || data.length > Math.ceil(MAX_IMAGE_BYTES / 3) * 4) {
      throw new ImageInputError(`第 ${n} 张图片为空或超过 ${MAX_IMAGE_BYTES / 1024 / 1024} MB`)
    }
    if (data.length % 4 !== 0 || !BASE64_CHARS.test(data)) throw new ImageInputError(`第 ${n} 张图片不是合法的 base64`)
    const bytes = Buffer.from(data, 'base64')
    // Buffer.from 会悄悄跳过不规范的部分（例如填充之前多余的位），解回去再编码一次必须原样相同
    if (bytes.toString('base64') !== data) throw new ImageInputError(`第 ${n} 张图片不是合法的 base64`)
    if (bytes.length > MAX_IMAGE_BYTES) throw new ImageInputError(`第 ${n} 张图片超过 ${MAX_IMAGE_BYTES / 1024 / 1024} MB`)
    if (!hasSignature(bytes, image.mediaType)) throw new ImageInputError(`第 ${n} 张图片的内容和格式不符`)
    return { type: 'image', mediaType: image.mediaType, data }
  })
}

/**
 * @typedef {object} ImagePart 消息里的一张图片（protocol.md 5.2）
 * @property {'image'} type
 * @property {string} id 64 位十六进制
 * @property {string} mediaType
 * @property {number} width
 * @property {number} height
 */

/**
 * DSH 会话记录里的图片引用 → 消息里的图片。引用不完整（DSH 以后改了格式）时返回 undefined，调用方退回占位文字。
 * @param {unknown} attachment user/message 里 image 块的 attachment
 * @returns {ImagePart | undefined}
 */
export function imagePart(attachment) {
  if (!isObject(attachment) || typeof attachment.attachmentId !== 'string') return undefined
  const match = ATTACHMENT_ID.exec(attachment.attachmentId)
  if (!match || typeof attachment.mediaType !== 'string' || !READ_TYPES.has(attachment.mediaType)) return undefined
  if (!isPositiveInt(attachment.width) || !isPositiveInt(attachment.height)) return undefined
  return { type: 'image', id: /** @type {string} */ (match[1]), mediaType: attachment.mediaType, width: attachment.width, height: attachment.height }
}

/**
 * 从一段会话记录里找出用户消息中的图片引用（和 transcript.js 显示的消息范围一致：用户亲自发的消息）。
 * @param {readonly unknown[]} records
 * @returns {Map<string, Record<string, unknown>>} 图片 id → DSH 的完整引用
 */
export function imageRefs(records) {
  /** @type {Map<string, Record<string, unknown>>} */
  const refs = new Map()
  for (const record of records) {
    if (!isObject(record) || record.type !== 'event' || !isObject(record.event) || record.event.type !== 'user/message') continue
    const data = record.event.data
    if (!isObject(data) || !isObject(data.source) || data.source.kind !== 'user' || !Array.isArray(data.content)) continue
    for (const block of data.content) {
      if (!isObject(block) || block.type !== 'image') continue
      const part = imagePart(block.attachment)
      if (part) refs.set(part.id, /** @type {Record<string, unknown>} */ (block.attachment))
    }
  }
  return refs
}

/**
 * 模型的 inputModalities → 协议里的 images：含 image 为 true，有这项但不含 image 为 false，没有这项为 undefined（不知道）。
 * @param {unknown} modalities
 * @returns {boolean | undefined}
 */
export function imagesFromModalities(modalities) {
  if (!Array.isArray(modalities)) return undefined
  return modalities.includes('image')
}

/**
 * 记住每个会话里见过的图片引用，读图片时先查这里。按会话数量限制，最久没用的先丢。
 */
export class ImageRefCache {
  /** @type {Map<string, Map<string, Record<string, unknown>>>} */
  #sessions = new Map()
  #maxSessions

  /** @param {number} [maxSessions] */
  constructor(maxSessions = 200) {
    this.#maxSessions = maxSessions
  }

  /** @param {string} sessionId @param {Map<string, Record<string, unknown>>} refs */
  add(sessionId, refs) {
    if (refs.size === 0) return
    const known = this.#sessions.get(sessionId) ?? new Map()
    for (const [id, ref] of refs) known.set(id, ref)
    this.#touch(sessionId, known)
  }

  /** @param {string} sessionId @param {string} id */
  get(sessionId, id) {
    const known = this.#sessions.get(sessionId)
    const ref = known?.get(id)
    if (known && ref) this.#touch(sessionId, known)
    return ref
  }

  /** @param {string} sessionId @param {Map<string, Record<string, unknown>>} known */
  #touch(sessionId, known) {
    this.#sessions.delete(sessionId)
    this.#sessions.set(sessionId, known)
    while (this.#sessions.size > this.#maxSessions) {
      const oldest = this.#sessions.keys().next().value
      if (oldest === undefined) break
      this.#sessions.delete(oldest)
    }
  }
}
