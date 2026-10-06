import { platformCredentials } from './credential-session.ts'
import { platformClient } from './http.ts'

export async function readLocalPreviewSession(signal: AbortSignal) {
  const before = platformCredentials.capture(Date.now(), true)
  const value = await platformClient.request('/__opsweave/local-session', { signal, bootstrap: true, responseBytes: 1024, timeoutMs: 10000 })
  if (signal.aborted || !platformCredentials.current(before)) throw new DOMException('会话已变化', 'AbortError')
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== 2
    || !('sessionNonce' in value) || typeof value.sessionNonce !== 'string'
    || !('expiresAt' in value) || typeof value.expiresAt !== 'string') throw new Error('本地会话响应不正确')
  platformCredentials.acceptLocalSession(value.sessionNonce, value.expiresAt)
}
