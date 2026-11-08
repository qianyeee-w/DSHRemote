// @ts-check
/**
 * 最小的 DER（ASN.1 Distinguished Encoding Rules）编码器。
 *
 * 只实现生成一张 ECDSA P-256 自签名证书所需的几种类型。
 * 不做解析：证书的解析和校验交给 Node 自带的 X509Certificate（测试里会用它验证输出）。
 */

const TAG = Object.freeze({
  INTEGER: 0x02,
  BIT_STRING: 0x03,
  OCTET_STRING: 0x04,
  NULL: 0x05,
  OID: 0x06,
  UTF8_STRING: 0x0c,
  UTC_TIME: 0x17,
  GENERALIZED_TIME: 0x18,
  SEQUENCE: 0x30,
  SET: 0x31,
})

/**
 * 编码长度：小于 128 用短格式，否则用长格式。
 * @param {number} length
 * @returns {Buffer}
 */
export function encodeLength(length) {
  if (!Number.isInteger(length) || length < 0) throw new RangeError(`非法长度：${length}`)
  if (length < 0x80) return Buffer.from([length])
  const bytes = []
  for (let n = length; n > 0; n = Math.floor(n / 256)) bytes.unshift(n & 0xff)
  if (bytes.length > 4) throw new RangeError(`长度过大：${length}`)
  return Buffer.from([0x80 | bytes.length, ...bytes])
}

/**
 * 一个 TLV（tag-length-value）。
 * @param {number} tag
 * @param {Buffer} value
 * @returns {Buffer}
 */
export function tlv(tag, value) {
  return Buffer.concat([Buffer.from([tag]), encodeLength(value.length), value])
}

/** @param {Buffer[]} items */
export const sequence = (...items) => tlv(TAG.SEQUENCE, Buffer.concat(items))

/** @param {Buffer[]} items */
export const set = (...items) => tlv(TAG.SET, Buffer.concat(items))

export const nullValue = () => tlv(TAG.NULL, Buffer.alloc(0))

/**
 * 无符号大整数（例如序列号）。去掉多余的前导 0；最高位为 1 时补一个 0，保证按正数解读。
 * @param {Buffer} bytes 大端字节
 * @returns {Buffer}
 */
export function unsignedInteger(bytes) {
  let start = 0
  while (start < bytes.length - 1 && bytes[start] === 0) start++
  let value = bytes.subarray(start)
  if (value.length === 0) value = Buffer.from([0])
  if (/** @type {number} */ (value[0]) & 0x80) value = Buffer.concat([Buffer.from([0]), value])
  return tlv(TAG.INTEGER, value)
}

/**
 * 小的非负整数（例如版本号）。
 * @param {number} n
 * @returns {Buffer}
 */
export function smallInteger(n) {
  if (!Number.isInteger(n) || n < 0 || n > 0x7fffffff) throw new RangeError(`不支持的整数：${n}`)
  const bytes = []
  for (let v = n; v > 0; v = Math.floor(v / 256)) bytes.unshift(v & 0xff)
  return unsignedInteger(Buffer.from(bytes.length === 0 ? [0] : bytes))
}

/**
 * 对象标识符，例如 '1.2.840.10045.4.3.2'。
 * @param {string} dotted
 * @returns {Buffer}
 */
export function oid(dotted) {
  const parts = dotted.split('.').map((p) => {
    if (!/^\d+$/.test(p)) throw new RangeError(`非法 OID：${dotted}`)
    return Number(p)
  })
  const [first, second, ...rest] = parts
  if (first === undefined || second === undefined || first > 2 || (first < 2 && second > 39)) {
    throw new RangeError(`非法 OID：${dotted}`)
  }
  const bytes = []
  for (const arc of [first * 40 + second, ...rest]) {
    const group = [arc & 0x7f]
    for (let v = Math.floor(arc / 128); v > 0; v = Math.floor(v / 128)) group.unshift((v & 0x7f) | 0x80)
    bytes.push(...group)
  }
  return tlv(TAG.OID, Buffer.from(bytes))
}

/** @param {string} text */
export const utf8String = (text) => tlv(TAG.UTF8_STRING, Buffer.from(text, 'utf8'))

/** @param {Buffer} bytes */
export const octetString = (bytes) => tlv(TAG.OCTET_STRING, bytes)

/**
 * BIT STRING，未使用位数固定为 0（证书里的签名和扩展都是整字节）。
 * @param {Buffer} bytes
 */
export const bitString = (bytes) => tlv(TAG.BIT_STRING, Buffer.concat([Buffer.from([0]), bytes]))

/** BOOLEAN TRUE（DER 中 TRUE 必须编码为 0xff）。 */
export const booleanTrue = () => tlv(0x01, Buffer.from([0xff]))

/**
 * X.509 的时间：2050 年之前用 UTCTime，之后用 GeneralizedTime（RFC 5280 4.1.2.5）。
 * 精确到秒，时区为 UTC。
 * @param {Date} date
 * @returns {Buffer}
 */
export function time(date) {
  if (Number.isNaN(date.getTime())) throw new RangeError('非法时间')
  const pad = (/** @type {number} */ n, width = 2) => String(n).padStart(width, '0')
  const year = date.getUTCFullYear()
  const rest = `${pad(date.getUTCMonth() + 1)}${pad(date.getUTCDate())}${pad(date.getUTCHours())}${pad(date.getUTCMinutes())}${pad(date.getUTCSeconds())}Z`
  if (year >= 1950 && year < 2050) return tlv(TAG.UTC_TIME, Buffer.from(`${pad(year % 100)}${rest}`, 'ascii'))
  if (year < 0 || year > 9999) throw new RangeError(`年份超出范围：${year}`)
  return tlv(TAG.GENERALIZED_TIME, Buffer.from(`${pad(year, 4)}${rest}`, 'ascii'))
}

/**
 * 上下文相关的显式标签 [n]（constructed）。
 * @param {number} n
 * @param {Buffer} inner
 */
export const explicit = (n, inner) => {
  if (!Number.isInteger(n) || n < 0 || n > 30) throw new RangeError(`不支持的标签号：${n}`)
  return tlv(0xa0 | n, inner)
}
