import http from 'node:http'; import { randomUUID } from 'node:crypto'
const launch = new URL(process.argv[2]); const H = launch.host
const req = (path, o = {}) => new Promise((ok, no) => { const r = http.request(`http://${H}${path}`, { method: o.method ?? 'GET', headers: { host: H, ...o.headers } }, m => { let b = ''; m.on('data', d => b += d); m.on('end', () => ok({ h: m.headers, b })) }); r.on('error', no); if (o.body) r.write(o.body); r.end() })
const cookie = (await req(`/?token=${launch.searchParams.get('token')}`)).h['set-cookie'][0].split(';')[0]
const rpc = async (m, args) => JSON.parse((await req(`/api/${m}`, { method: 'POST', headers: { cookie, 'content-type': 'application/json' }, body: JSON.stringify({ type: 'client-request', rpcId: randomUUID(), method: m, payload: { args } }) })).b).result
const list = await rpc('session/list', { _request: {} })
const sessionId = list.value.items[0].sessionId
const p1 = await rpc('session/page', { request: { address: { kind: 'session', sessionId }, maxMessages: 2 } })
const seqs = r => r.value.records.filter(x => x.type === 'event').map(x => x.event.seq)
console.log('page1 seqs', seqs(p1), 'keys', Object.keys(p1.value))
const first = Math.min(...seqs(p1))
const p2 = await rpc('session/page', { request: { address: { kind: 'session', sessionId }, maxMessages: 2, beforeSeq: first } })
console.log('page2 seqs', seqs(p2), 'all < first:', seqs(p2).every(s => s < first))
