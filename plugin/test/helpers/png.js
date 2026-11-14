// @ts-check
/**
 * 生成合法的 PNG（DSH 会解码校验图片，随便拼的字节过不了）。测试、契约测试和 dev-server 共用。
 */
import { deflateSync } from 'node:zlib'

/** @type {number[]} */
const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})

/** @param {Buffer} bytes */
function crc32(bytes) {
  let c = 0xffffffff
  for (const b of bytes) c = /** @type {number} */ (CRC_TABLE[(c ^ b) & 0xff]) ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

/** @param {string} type @param {Buffer} data */
function chunk(type, data) {
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body))
  return Buffer.concat([length, body, crc])
}

/**
 * 一张纯色的 RGBA PNG。
 * @param {number} width @param {number} height @param {[number, number, number, number]} [rgba]
 * @returns {Buffer}
 */
export function solidPng(width, height, rgba = [200, 80, 40, 255]) {
  const header = Buffer.alloc(13)
  header.writeUInt32BE(width, 0)
  header.writeUInt32BE(height, 4)
  header[8] = 8 // 位深
  header[9] = 6 // RGBA
  const row = Buffer.alloc(1 + width * 4) // 每行开头一个过滤类型字节（0）
  for (let x = 0; x < width; x++) row.set(rgba, 1 + x * 4)
  const raw = Buffer.concat(Array.from({ length: height }, () => row))
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ])
}
