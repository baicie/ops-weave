import { expect, test } from '@playwright/test'
import { CredentialSession } from '../src/api/credential-session.ts'
import { JsonClient } from '../src/api/http.ts'
import { TOKEN_OK } from './helpers.ts'

const signal = () => new AbortController().signal
function setup(fetcher: typeof fetch, surface: 'platform' | 'fixture-demo' = 'platform') {
  const session = new CredentialSession(); session.replace(TOKEN_OK)
  return { session, client: new JsonClient(session, surface, fetcher) }
}
test('shared transport uses one bounded same-origin request and omits cookies and referrers', async () => {
  const calls: RequestInit[] = []
  const { client } = setup(async (_url, options) => { calls.push(options!); return Response.json({ okay: true }, { headers: { 'X-OpsWeave-Request-Id': (options!.headers as Record<string, string>)['X-OpsWeave-Request-Id']! } }) })
  expect(await client.request('/api/v1/incidents', { signal: signal() })).toEqual({ okay: true })
  expect(calls).toHaveLength(1); expect(calls[0]).toMatchObject({ credentials: 'omit', mode: 'same-origin', redirect: 'error', cache: 'no-store', referrerPolicy: 'no-referrer' })
  expect(calls[0]!.headers).toMatchObject({ Authorization: `Bearer ${TOKEN_OK}` }); expect(calls[0]!.signal!.aborted).toBe(false)
})
test('platform and fixture Runtime credentials cannot cross API surfaces or follow remote paths', async () => {
  let calls = 0; const { client } = setup(async () => { calls++; return Response.json({}) })
  for (const path of ['https://other.invalid/api/v1/entities', '//other.invalid/api/v1/entities', '/agent/api/v1/diagnoses', '/api/v1/../../agent/api/v1/diagnoses', '/api/v1/entities#secret', '/api/v1/entities\\else']) {
    await expect(client.request(path, { signal: signal() })).rejects.toThrow('允许的 API 范围')
  }
  const demo = setup(async () => { calls++; return Response.json({}) }, 'fixture-demo')
  await expect(demo.client.request('/api/v1/entities', { signal: signal() })).rejects.toThrow('允许的 API 范围')
  expect(calls).toBe(0)
})
test('rejects oversized advertised and streamed responses and cancels the reader', async () => {
  let canceled = 0
  const advertised = setup(async () => new Response(new ReadableStream({ cancel() { canceled++ } }), { headers: { 'Content-Type': 'application/json', 'Content-Length': '2097153' } }))
  await expect(advertised.client.request('/api/v1/entities', { signal: signal() })).rejects.toThrow('响应读取失败')
  const streamed = setup(async () => new Response(new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(9)) }, cancel() { canceled++ } }), { headers: { 'Content-Type': 'application/json' } }))
  await expect(streamed.client.request('/api/v1/entities', { signal: signal(), responseBytes: 8 })).rejects.toThrow('响应读取失败')
  expect(canceled).toBe(2)
})
test('rejects invalid UTF-8, non-JSON and a mismatched response correlation ID', async () => {
  const responses = [new Response(Uint8Array.from([0xc3, 0x28]), { headers: { 'Content-Type': 'application/json' } }), new Response('<h1>Login</h1>', { headers: { 'Content-Type': 'text/html' } }),
    Response.json({}, { headers: { 'X-OpsWeave-Request-Id': '11111111-1111-4111-8111-111111111111' } })]
  for (const response of responses) { const { client } = setup(async () => response); await expect(client.request('/api/v1/entities', { signal: signal() })).rejects.toThrow() }
})
test('body deadline aborts a stalled stream and releases the request slot', async () => {
  let canceled = false; let calls = 0
  const { client } = setup(async () => ++calls === 1 ? new Response(new ReadableStream({ cancel() { canceled = true } }), { headers: { 'Content-Type': 'application/json' } }) : Response.json({ okay: true }))
  await expect(client.request('/api/v1/entities', { signal: signal(), timeoutMs: 20 })).rejects.toThrow('请求超时')
  expect(canceled).toBe(true); expect(await client.request('/api/v1/entities', { signal: signal() })).toEqual({ okay: true })
})
test('replaced credentials discard a late unauthorized response without clearing the new identity', async () => {
  let finish!: (value: Response) => void; const response = new Promise<Response>(resolve => { finish = resolve })
  const { session, client } = setup(async () => response); const changes: string[] = []; session.subscribe(change => changes.push(change.reason))
  const pending = client.request('/api/v1/entities', { signal: signal() }); session.replace(TOKEN_OK + '-new')
  finish(Response.json({}, { status: 401 })); await expect(pending).rejects.toThrow('会话已变化')
  expect(session.token()).toBe(TOKEN_OK + '-new'); expect(changes).toEqual(['credentials'])
})
test('401 clears credentials and 403 invalidates all data while retaining the development credential', async () => {
  for (const status of [401, 403]) {
    const { session, client } = setup(async () => Response.json({ error: 'secret customer text' }, { status }))
    const old = session.capture(); const messages: string[] = []; session.subscribe(change => messages.push(change.error?.message ?? ''))
    await expect(client.request('/api/v1/entities', { signal: signal() })).rejects.toThrow(`HTTP ${status}`)
    expect(session.current(old)).toBe(false); expect(session.token()).toBe(status === 401 ? '' : TOKEN_OK)
    expect(messages).toHaveLength(1); expect(messages[0]).not.toContain('secret customer text')
  }
})
test('six requests share capacity and logout cancels them without hidden retries', async () => {
  let calls = 0; const pending: Array<(value: Response) => void> = []
  const { session, client } = setup(async () => { calls++; return new Promise(resolve => pending.push(resolve)) })
  const requests = Array.from({ length: 6 }, () => client.request('/api/v1/entities', { signal: signal() }).catch(error => error))
  await expect(client.request('/api/v1/entities', { signal: signal() })).rejects.toThrow('并发请求已达上限')
  session.clear(); for (const complete of pending) complete(Response.json({ stale: true }))
  for (const result of await Promise.all(requests)) expect(result).toBeInstanceOf(DOMException)
  expect(calls).toBe(6); expect(session.token()).toBe('')
})
test('development credential expiry is absolute and does not extend on reads', () => {
  const session = new CredentialSession(); session.replace(TOKEN_OK, 1000)
  const ticket = session.capture(1001); expect(session.current(ticket)).toBe(true)
  session.capture(1800999); expect(session.expiresAt()).toBe(1801000)
  expect(() => session.capture(1801000)).toThrow(); expect(session.token()).toBe(''); expect(session.current(ticket)).toBe(false)
})
test('request body limit and prior cancellation stop before network dispatch', async () => {
  let calls = 0; const { client } = setup(async () => { calls++; return Response.json({}) })
  await expect(client.request('/api/v1/incidents', { signal: signal(), body: { text: 'x'.repeat(65536) } })).rejects.toThrow('请求正文超过限制')
  const canceled = new AbortController(); canceled.abort()
  await expect(client.request('/api/v1/incidents', { signal: canceled.signal })).rejects.toThrow('请求已取消')
  expect(calls).toBe(0)
})
test('a rejected replacement credential cannot leave the prior identity active', () => {
  const session = new CredentialSession(); session.replace(TOKEN_OK)
  expect(() => session.replace('invalid\ncredential')).toThrow('凭据格式不正确')
  expect(session.token()).toBe('')
})
