// @ts-check
/**
 * 图片附件（protocol.md 第 4 节「图片」，ADR 0014）：发送前的校验、会话记录里的引用、模型能否看图。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  ImageInputError, ImageRefCache, MAX_IMAGE_BYTES, MAX_IMAGES, imagePart, imageRefs, imagesFromModalities, parseOutgoingImages,
} from '../../src/dsh/images.js'

const PNG = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.from('rest')]).toString('base64')
const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3]).toString('base64')
const HEX = 'ab'.repeat(32)

/** DSH 会话记录里的一张图片引用（格式见调研文档 9.7） */
const ref = (hex = HEX, extra = {}) => ({ attachmentId: `sha256:${hex}`, mediaType: 'image/png', width: 1280, height: 720, bytes: 2048, name: 'a.png', ...extra })
/** @param {unknown[]} content @param {string} [kind] @param {number} [seq] */
const userMessage = (content, kind = 'user', seq = 1) => ({
  type: 'event',
  event: { type: 'user/message', seq, time: 1, data: { content, source: { kind }, role: 'user', id: `m${seq}` }, surfaceOp: 'append' },
})

test('发送：JPEG、PNG 转换成 DSH 的 image 内容块；没有 images 时为空', () => {
  assert.deepEqual(parseOutgoingImages(undefined), [])
  assert.deepEqual(parseOutgoingImages([{ mediaType: 'image/png', data: PNG }, { mediaType: 'image/jpeg', data: JPEG }]), [
    { type: 'image', mediaType: 'image/png', data: PNG },
    { type: 'image', mediaType: 'image/jpeg', data: JPEG },
  ])
})

test('【反向】发送：格式、张数、大小、base64、文件头不对的图片都拒绝', () => {
  const big = Buffer.alloc(MAX_IMAGE_BYTES + 1)
  big.set([0xff, 0xd8, 0xff])
  const cases = [
    'x',
    {},
    Array.from({ length: MAX_IMAGES + 1 }, () => ({ mediaType: 'image/png', data: PNG })),
    [null],
    [{ mediaType: 'image/gif', data: PNG }],
    [{ mediaType: 'image/svg+xml', data: PNG }],
    [{ mediaType: 'image/png' }],
    [{ mediaType: 'image/png', data: '' }],
    [{ mediaType: 'image/png', data: `data:image/png;base64,${PNG}` }],
    [{ mediaType: 'image/png', data: 'iVBORw0KGgo' }], // 缺填充
    [{ mediaType: 'image/png', data: PNG.replace(/=*$/, '') + '-_' }],
    [{ mediaType: 'image/png', data: JPEG }], // 内容是 JPEG，声称是 PNG
    [{ mediaType: 'image/jpeg', data: Buffer.from('<svg/>').toString('base64') }],
    [{ mediaType: 'image/jpeg', data: big.toString('base64') }],
    [{ mediaType: 'image/jpeg', data: 'A'.repeat(Math.ceil(MAX_IMAGE_BYTES / 3) * 4 + 4) }],
    [{ mediaType: 'image/png', data: 'iVBORw0KGgp=' }], // PNG 文件头，但填充前多余的两位不是 0：不是规范的 base64（规范的是 iVBORw0KGgo=）
  ]
  for (const images of cases) assert.throws(() => parseOutgoingImages(images), ImageInputError, JSON.stringify(images).slice(0, 60))
})

test('发送：接近上限（8 MB）的图片能通过校验，不会耗尽调用栈', () => {
  const bytes = Buffer.alloc(MAX_IMAGE_BYTES)
  bytes.set([0xff, 0xd8, 0xff])
  assert.equal(parseOutgoingImages([{ mediaType: 'image/jpeg', data: bytes.toString('base64') }]).length, 1)
})

test('会话记录里的引用 → 消息里的图片：id 是去掉 sha256: 的十六进制；引用不完整时为 undefined', () => {
  assert.deepEqual(imagePart(ref()), { type: 'image', id: HEX, mediaType: 'image/png', width: 1280, height: 720 })
  assert.deepEqual(imagePart(ref(HEX, { mediaType: 'image/webp' }))?.mediaType, 'image/webp')
  for (const bad of [null, {}, ref('AB'.repeat(32)), ref('ab'), ref(HEX, { attachmentId: `md5:${HEX}` }), ref(HEX, { mediaType: 'image/svg+xml' }),
    ref(HEX, { width: 0 }), ref(HEX, { height: 1.5 }), ref(HEX, { width: '10' })]) {
    assert.equal(imagePart(bad), undefined, JSON.stringify(bad))
  }
})

test('只收集用户亲自发的消息里的图片引用', () => {
  const other = 'cd'.repeat(32)
  const refs = imageRefs([
    userMessage([{ type: 'text', text: 'hi' }, { type: 'image', attachment: ref() }]),
    userMessage([{ type: 'image', attachment: ref(other) }], 'runtime-context', 2),
    userMessage([{ type: 'image', attachment: ref('ef') }], 'user', 3),
    { type: 'event', event: { type: 'tool/result', seq: 4, data: { content: [{ type: 'image', attachment: ref(other) }] } } },
  ])
  assert.deepEqual([...refs.keys()], [HEX])
  assert.deepEqual(refs.get(HEX), ref())
})

test('模型能否看图：含 image 为 true，有这项但不含为 false，没有这项为不知道', () => {
  assert.equal(imagesFromModalities(['text', 'image']), true)
  assert.equal(imagesFromModalities(['text']), false)
  assert.equal(imagesFromModalities([]), false)
  assert.equal(imagesFromModalities(undefined), undefined)
  assert.equal(imagesFromModalities('image'), undefined)
})

test('引用缓存按会话区分，超过会话数时丢掉最久没用的', () => {
  const cache = new ImageRefCache(2)
  cache.add('s1', new Map([[HEX, ref()]]))
  cache.add('s2', new Map([['cd'.repeat(32), ref('cd'.repeat(32))]]))
  assert.equal(cache.get('s2', HEX), undefined, '别的会话的图片读不到')
  assert.ok(cache.get('s1', HEX)) // s1 变成最近用过
  cache.add('s3', new Map([['ef'.repeat(32), ref('ef'.repeat(32))]]))
  assert.ok(cache.get('s1', HEX))
  assert.equal(cache.get('s2', 'cd'.repeat(32)), undefined)
})
