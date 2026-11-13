// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { convertPlan, GOAL_OBJECTIVE_LIMIT, STEPS_LIMIT, STEP_TEXT_LIMIT } from '../../src/dsh/plan.js'

// 取自契约测试里真实 DSH 的 session/projections（TODO、GOAL 关键词之后）
const goalProjection = {
  goal: {
    id: 'goal-5f68714f-51cd-4c44-bafa-71dd67a65443',
    revision: 2,
    objective: '确认断线重连以后还能接着看当前会话',
    phase: 'blocked',
    blockedReason: { code: 'round-limit', message: 'Goal reached its configured limit of 1 rounds.' },
    maxGoalRounds: 1,
  },
  roundsStarted: 1,
  createdAt: 1794603865193,
  updatedAt: 1794603865267,
}
const todos = [
  { content: '读连接状态的代码', status: 'completed' },
  { content: '跑单元测试', status: 'in_progress' },
  { content: '汇报结论', status: 'pending' },
]

test('计划：只提取目标的描述、状态、受阻原因，和任务清单；其他字段不转发', () => {
  const plan = convertPlan({ kind: 'sequenced', asOfSeq: 5, values: { goal: goalProjection, todos, plan: { active: false, pending: false }, workingDirectory: '/home/me/x' } })
  assert.deepEqual(plan, {
    goal: { objective: '确认断线重连以后还能接着看当前会话', status: 'blocked', blocked: { code: 'round-limit', message: 'Goal reached its configured limit of 1 rounds.' } },
    steps: [
      { text: '读连接状态的代码', status: 'completed' },
      { text: '跑单元测试', status: 'in_progress' },
      { text: '汇报结论', status: 'pending' },
    ],
  })
  const text = JSON.stringify(plan)
  for (const hidden of ['goal-5f68', 'revision', 'roundsStarted', '/home/me']) assert.ok(!text.includes(hidden), hidden)
})

test('计划：拿不到时为 null；不认识的状态、非法的受阻原因、超长和过多的内容', () => {
  assert.deepEqual(convertPlan(null), { goal: null, steps: null })
  assert.deepEqual(convertPlan({ values: { goal: null, todos: null } }), { goal: null, steps: null })
  // 新一轮开始、模型还没写之前 DSH 给 null；写了空清单就是空数组
  assert.deepEqual(convertPlan({ values: { todos: [] } }).steps, [])
  assert.equal(convertPlan({ values: { goal: { goal: { objective: '目标', phase: 'archived' } } } }).goal?.status, 'other')
  // 不在受阻状态时不带原因；code 格式不对时不带
  assert.equal(convertPlan({ values: { goal: { goal: { ...goalProjection.goal, phase: 'active' } } } }).goal?.blocked, undefined)
  assert.equal(convertPlan({ values: { goal: { goal: { ...goalProjection.goal, blockedReason: { code: 'Bad Code!', message: 'x' } } } } }).goal?.blocked, undefined)
  assert.equal(convertPlan({ values: { goal: { goal: { objective: '  ', phase: 'active' } } } }).goal, null)
  const long = convertPlan({ values: { goal: { goal: { objective: 'x'.repeat(5_000), phase: 'active' } } } }).goal
  assert.equal(long?.objective.length, GOAL_OBJECTIVE_LIMIT)
  const many = convertPlan({ values: { todos: Array.from({ length: 150 }, (_, i) => ({ content: `${'y'.repeat(800)}${i}`, status: i % 2 ? 'done' : 'pending' })) } }).steps ?? []
  assert.equal(many.length, STEPS_LIMIT)
  assert.ok(many.every((s) => s.text.length === STEP_TEXT_LIMIT && s.status === 'pending'))
  // 空的一项跳过
  assert.deepEqual(convertPlan({ values: { todos: [{ content: '', status: 'pending' }, { status: 'pending' }, 'x', { content: '有效', status: 'completed' }] } }).steps, [{ text: '有效', status: 'completed' }])
})
