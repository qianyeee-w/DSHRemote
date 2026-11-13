// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { convertUsage } from '../../src/dsh/usage.js'

// 取自契约测试里真实 DSH 的 session/projections（跑完一轮之后）
const after = {
  kind: 'sequenced',
  asOfSeq: 20,
  values: {
    sessionStats: { turns: 1, steps: 1, llmMs: 40 },
    tokenUsage: { uncachedInputTokens: 10, outputTokens: 5, cacheReadTokens: 0, cacheWriteTokens: 0 },
    contextPressure: { pressureTokens: 10, projectedTokens: 21, contextWindow: 1048576 },
    contextBreakdown: { systemTokens: 1452, toolsTokens: 7476, messageTokens: 281 },
    workingDirectory: '/home/someone/project',
    title: '你好',
  },
}

test('用量：只提取上下文和累计用量，其他投影一概不转发', () => {
  const usage = convertUsage(after)
  assert.deepEqual(usage, {
    context: { tokens: 21, window: 1048576 },
    total: { uncachedInputTokens: 10, outputTokens: 5, cacheReadTokens: 0, cacheWriteTokens: 0 },
  })
  const text = JSON.stringify(usage)
  for (const secret of ['/home/someone', 'systemTokens', 'sessionStats', '你好']) assert.ok(!text.includes(secret), secret)
})

test('用量：拿不到的不用 0 代替', () => {
  // 新会话：contextPressure 是空对象
  assert.deepEqual(convertUsage({ kind: 'sequenced', values: { contextPressure: {}, tokenUsage: after.values.tokenUsage } }).context, null)
  // 还没有估算值时用最近一次请求的实际值；只有上限时只给上限
  assert.deepEqual(convertUsage({ values: { contextPressure: { pressureTokens: 10 } } }).context, { tokens: 10 })
  assert.deepEqual(convertUsage({ values: { contextPressure: { contextWindow: 131072 } } }).context, { window: 131072 })
  // 迁移中只有缓存提示的投影照样读取
  assert.deepEqual(convertUsage({ kind: 'migration-required', values: { tokenUsage: after.values.tokenUsage } }).total, after.values.tokenUsage)
  // 缺项、非法值、整个投影为 null
  assert.equal(convertUsage({ values: { tokenUsage: { uncachedInputTokens: 10, outputTokens: 5 } } }).total, null)
  assert.equal(convertUsage({ values: { tokenUsage: { ...after.values.tokenUsage, outputTokens: -1 } } }).total, null)
  assert.deepEqual(convertUsage({ values: { contextPressure: { projectedTokens: 1.5, contextWindow: 0 } } }).context, null)
  assert.deepEqual(convertUsage(null), { context: null, total: null })
})
